package org.python.ReL;

import oracle.kv.*;
import oracle.kv.table.PrimaryKey;
import oracle.kv.table.Row;
import oracle.kv.table.Table;
import oracle.kv.table.TableAPI;
import org.apache.commons.lang3.SerializationUtils;
import wdb.metadata.Adapter;
import wdb.metadata.ClassDef;
import wdb.metadata.IndexDef;
import wdb.metadata.WDBObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * @author Joshua Hurt
 */
public class OracleNoSQLDatabase extends DatabaseInterface {
    public static final boolean DBG = true;
    private File INSTALLATION_ROOT;

    /* Table Info */
    private static final String CLASSDEF_TABLE_NAME = "ClassDef";
    private static final String WDBOBJECT_TABLE_NAME = "WDBObject";

    /* Storage Node */
    private static final String STORE_NAME = "WDB";
    private static final String HOST = "localhost";
    private static final String PORT = "5000";
    private static final String ADMIN_PORT = "5001";
    private static final String HARANGE = "5010,5020";
    private String[] baseCommands;

    public static KVStore store;
    private static TableAPI tableH;
    private static Table classTable;
    private static Table objectTable;
    private static File storeRoot;
    private static Process storeProcess;
    private static File storageDir;





    /**
     * Connects to a KVStore on port 5000 if one exists, otherwise
     * creates a new KVStore.
     */
    public OracleNoSQLDatabase() {
        validateInstallationRoot();
        baseCommands = new String[]{"java", "-jar", INSTALLATION_ROOT.getAbsolutePath() + "/extlibs/kvstore.jar"};

        this.adapter = new OracleNoSQLAdapter(this);
        if (! reconnectToExistingStore()) {
            setupSNDirectories();
            setupEnvironmentAndCreateNodes();
            connectToStore();
            if (!DBG) {
                dropTable(classTable);
                dropTable(objectTable);
            }
            createTables();
        }
    }



    /*******************************************************
     ************* STORE CONNECTION METHODS ***************************
     *******************************************************/

    public boolean setupEnvironmentAndCreateNodes() {
        // Initialize Storage Node
        kvStoreCommand("makebootconfig",
                "-root", storeRoot.getAbsolutePath(),
                "-port", PORT,
                "-admin", ADMIN_PORT,
                "-host", HOST,
                "-harange", HARANGE,
                "-capacity", "1",
                "-num_cpus", "0",
                "-memory_mb", "0",
                "-store-security", "none",
                "-storagedir", storageDir.getAbsolutePath());


        kvStoreCommand("start", "-root", storeRoot.getAbsolutePath());

        final File wdbStoreSetupScript = new File(INSTALLATION_ROOT, "setup_WDB_store.txt");
        if (!wdbStoreSetupScript.exists())
            ultimateCleanUp("Cannot find setup script for storage node.");

        // (Yes, I meant 'PORT' and not 'ADMIN_PORT' since we need to connect on the registry port)
        kvStoreCommand("runadmin",
                "-port", PORT,
                "-host", HOST,
                "load", "-file", wdbStoreSetupScript.getAbsolutePath());

        return true;
    }

    public void kvStoreCommand(String... commandWithArgs) {
        final String command = commandWithArgs[0];
        ArrayList<String> arguments = new ArrayList<>(Arrays.asList(baseCommands));
        arguments.addAll(Arrays.asList(commandWithArgs));
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(INSTALLATION_ROOT);
        pb.command(arguments);

        if (DBG)
            System.out.println(String.format("Running command: '%s'", pb.command().toString()));

        try {
            Process utility = pb.start();
        /* The Storage Node Agent (SNA) should run in the background, so don't wait for it.
         * Save it so we can kill the process when we exit. */
            if (command.equalsIgnoreCase("start"))
                storeProcess = utility;
                // Don't want to wait for stop
            else if (command.equalsIgnoreCase("stop"))
                return;
            else {
                /* Need to setup the Node before we can use it
                 * so waitFor() makes us wait for the process to finish. */
                int status = utility.waitFor();

                if (status != 0) {
                    ultimateCleanUp("Process exited abnormally.");
                }
            }
        }
        catch (Exception e) {
            ultimateCleanUp(String.format("%s",e.getMessage()));
        }
    }

    public void validateInstallationRoot() {
        final String serverRoot = System.getenv("INSTALLATION_ROOT");
        if (serverRoot == null)
            ultimateCleanUp("Server not started. Please set INSTALLATION_ROOT environment variable.");

        INSTALLATION_ROOT = new File(serverRoot);
        if (!INSTALLATION_ROOT.isDirectory()) {
            ultimateCleanUp(String.format(
                    "Invalid INSTALLATION_ROOT value. '%s' is not a directory!",
                    INSTALLATION_ROOT));
        }
    }

    public void setupSNDirectories() {
        storageDir = new File(INSTALLATION_ROOT, "db/store");
        if(!storageDir.exists())
        {
            if (storageDir.mkdirs())
                System.out.println(String.format(
                        "Creating storage directory at '%s'", storageDir.getAbsolutePath()));
        }

        storeRoot = new File(INSTALLATION_ROOT, "/STORE");
        if (storeRoot.mkdirs())
            System.out.println(String.format(
                    "Creating Storage Node directory at '%s'", storeRoot.getAbsolutePath()));
    }


    /*******************************************************
     ************* TABLE METHODS ***************************
     *******************************************************/
    public void createTables() {
        createTable(classTable, CLASSDEF_TABLE_NAME);
        createTable(objectTable, WDBOBJECT_TABLE_NAME);
    }


    private void createTable(Table table, String tableName) {
//        if (!storeProcess.isAlive())
//            throw new IllegalThreadStateException("No connection to database!");
        tableH = store.getTableAPI();
        String statement;

        // If this succeeds then the tables have been already created
        // in a previous run.
        table = tableH.getTable(tableName);
        if (table == null) {
            try {
            /*
             * Add a table to the database.
             * Execute this statement asynchronously.
             */

                // lol just naming them 'key' and 'value'
                statement =
                        "CREATE TABLE " + tableName + " (" +
                                "key STRING," +
                                "value BINARY," +
                                "PRIMARY KEY (key))";

                store.executeSync(statement);
                table = tableH.getTable(tableName);

            } catch (IllegalArgumentException e) {
                ultimateCleanUp(String.format(
                        "Invalid statement: %s",
                        e.getMessage()));
            } catch (FaultException e) {
                ultimateCleanUp(String.format(
                        "Invalid statement: %s",
                        e.getMessage()));
            }
        }
        if (tableName.equalsIgnoreCase(CLASSDEF_TABLE_NAME))
            classTable = table;
        else if (tableName.equalsIgnoreCase(WDBOBJECT_TABLE_NAME))
            objectTable = table;
    }


    public void clearDatabase() {
        dropTable(classTable);
        dropTable(objectTable);
        createTables();

    }
    private void dropTable(Table table) {
        if (table == null)
            return;
        String statement =
                "DROP TABLE " +
                        table.getFullName();

        try {
            System.out.println(String.format("Dropping table %s ...",table.getFullName()));
            store.executeSync(statement);
            System.out.println("Success!");
        }
        catch (IllegalArgumentException e) {
            System.out.println("Invalid statement:\n" + e.getMessage());
            System.out.println("Well crap.");
        }
        catch (FaultException e) {
            System.out.println
                    ("Statement couldn't be executed, please retry: " + e);
            System.out.println("Well crap.");
        }
    }


    private void connectToStore() {
        // Obtain handles to the running Storage Node
        try {
            System.out.println("Trying to connect to the store ...");
            store = KVStoreFactory.getStore
                    (new KVStoreConfig(STORE_NAME, HOST + ":" + PORT));
        }
        catch (FaultException e) {
            ultimateCleanUp("Connection failed! Cleaning up...");
            if (DBG)
                System.out.println(String.
                        format("'%s' in connectToStore", e.getMessage()));
        }
        System.out.println("Connection successful!");
    }

    private boolean reconnectToExistingStore() {
        // Obtain handles to the running Storage Node
        try {
            System.out.println("Trying to connect to an already running store ...");
            store = KVStoreFactory.getStore
                    (new KVStoreConfig(STORE_NAME, HOST + ":" + PORT));
        }
        catch (FaultException e) {
            System.out.println("No existing connection found. Creating new one.");
            return false;
        }
        setupSNDirectories();
        createTables();
        System.out.println("Connection successful!");
        return true;
    }

    private void disconnectFromStore() {
        if (store != null)
            store.close();
    }


    /**
     * Remove the directories created during initializing so we
     * can get a fresh start next try. Close connections to KVStores
     * and terminate background server processes.
     */
    public void ultimateCleanUp(String reason) {
        disconnectFromStore();
        // Explicitly run stop if server is running
        if (storeRoot != null)
            kvStoreCommand("stop", "-root", storeRoot.getAbsolutePath());

        // Below may not be needed if above works quickly
        if (storeProcess != null)
            storeProcess.destroy();
        System.out.println("========="+reason+"=========");
        System.exit(-1);
    }

    public String getStorageDirPath() {
        return storageDir.getAbsolutePath();
    }

    public TableAPI getTableHandle() {
        return tableH;
    }

    public Table getClassTable() {
        return classTable;
    }

    public Table getObjectTable() {
        return objectTable;
    }


    /**
     * OracleNoSQLAdapter implements the Adapter interface to ensure it has all the
     * required methods implemented to be processed in ProcessLanguages.
     * @author Joshua Hurt
     */
    private class OracleNoSQLAdapter implements Adapter {
        private OracleNoSQLDatabase db;
        private static final String classKeyPrefix = "class";
        private static final String objectKeyPrefix = "object";

        private OracleNoSQLAdapter(OracleNoSQLDatabase db) {
            this.db = db;
        }

        /**
         * key: String class:(classDef.name)
         * value: ClassDef classDef
         */
        public void putClass(ClassDef classDef) {
            final String keyString = makeClassKey(classDef.name);
            final byte[] data = SerializationUtils.serialize(classDef);

            Row row = db.getClassTable().createRow();
            row.put("key", keyString);
            row.put("value", data);

            db.getTableHandle().put(row, null, null);
        }

        /**
         * key: String class:(classDef.name)
         * @return ClassDef or null if not found
         */
        public ClassDef getClass(String className) throws ClassNotFoundException {
            PrimaryKey key = db.getClassTable().createPrimaryKey();
            final String keyString = makeClassKey(className);
            key.put("key", keyString);
            Row row = db.getTableHandle().get(key, null);

            if (row == null)
                throw new ClassNotFoundException(String.format(
                        "Key '%s' not present in table", keyString));
            byte[] data = row.get("value").asBinary().get();

            final ClassDef classDef = (ClassDef) SerializationUtils.deserialize(data);
            if (classDef == null) {
                db.ultimateCleanUp(String.format(
                        "Null value returned from ClassesDB lookup for class: %s",
                        keyString));
            }

            return classDef;
        }

        /**
         * key: String object:(Uid.toString())
         * value: WDBObject object
         * @param wdbObject to serialize and store as value
         */
        public void putObject(WDBObject wdbObject) {
            final String keyString = makeObjectKey(wdbObject.getUid());
            final byte[] data = SerializationUtils.serialize(wdbObject);

            Row row = db.getObjectTable().createRow();
            row.put("key", keyString);
            row.put("value", data);

            db.getTableHandle().put(row, null, null);
        }

        /**
         * key: String object:(Uid.toString())
         * value: WDBObject object
         * @param className only used for MissingResourceException
         * @param Uid is the key to retrieve the WDBObject
         * @return WDBObject or throws MissingResourceException
         */
        public WDBObject getObject(String className, Integer Uid) {
            final String keyString = makeObjectKey(Uid);
            PrimaryKey key = db.getObjectTable().createPrimaryKey();
            key.put("key", keyString);
            Row row = db.getTableHandle().get(key, null);

            byte[] data = row.get("value").asBinary().get();

            final WDBObject wdbObject = (WDBObject) SerializationUtils.deserialize(data);
            if (wdbObject == null) {
                db.ultimateCleanUp(String.format(
                        "Null value returned from ClassesDB lookup for class: %s",
                        keyString));
            }

            return wdbObject;
        }

        public ArrayList<WDBObject> getObjects(IndexDef indexDef, String key) {
            System.out.println("getObjects called in OracleNoSQLAdapter");
            return null;
        }

        private String makeClassKey(String className) {
            return classKeyPrefix + ":" + className;
        }

        private String makeObjectKey(Integer Uid) {
            return objectKeyPrefix + ":" + Uid.toString();
        }
        public void abort() {

        }

        public void commit() {

        }
    }
}