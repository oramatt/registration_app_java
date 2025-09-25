import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.*;
import org.bson.Document;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;

public class MongoDBApp {

    public static void main(String[] args) {
        // ensure file is in same relative location as class file
        String[] dbConfig = readDbConfig("mongoConn.txt"); 

        if (dbConfig == null || dbConfig.length < 2) {
            System.out.println("Failed to read the MongoDB connection details. Please check mongoConn.txt.");
            return;
        }

        String connectionString = dbConfig[0];
        String databaseName = dbConfig[1];

        System.out.println("Connecting with connection string: " + connectionString);
        System.out.println("Database: " + databaseName);

        try {
            MongoClient mongoClient = createMongoClient(connectionString);
            MongoDatabase database = mongoClient.getDatabase(databaseName);

            // check permissions on databases/collections for user
            checkAccessibleDatabasesAndCollections(mongoClient);

            // check roles/privileges for user
            checkUserRolesAndPrivileges(database);

            // test connection
            for (String name : database.listCollectionNames()) {
                System.out.println("Found collection: " + name);
            }

            System.out.println("Connected successfully.");
            runApp(database, mongoClient);
        } catch (Exception e) {
            System.err.println("Connection failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static MongoClient createMongoClient(String uri) {
        ConnectionString connString = new ConnectionString(uri);

        MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .applyConnectionString(connString);

        // honor tlsAllowInvalidCertificates=true as defined in mongoConn.txt
        String lowerUri = uri.toLowerCase(Locale.ROOT);
        if (lowerUri.contains("tlsallowinvalidcertificates=true")) {
            try {
                // force trusting all certificates like self-signed
                TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
                };
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, trustAllCerts, new SecureRandom());

                builder.applyToSslSettings(ssl -> {
                    ssl.context(sslContext);
                    ssl.invalidHostNameAllowed(true);
                });

                System.out.println("Warning: TLS certificate validation is disabled (tlsAllowInvalidCertificates=true).");
            } catch (Exception e) {
                System.err.println("Failed to disable TLS validation: " + e.getMessage());
            }
        }

        return MongoClients.create(builder.build());
    }

    private static String[] readDbConfig(String filename) {
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String uri = br.readLine();
            if (uri == null || uri.trim().isEmpty()) {
                System.err.println("mongoConn.txt is empty.");
                return null;
            }
            // parse out database name from mongoConn.txt
            // examples 
            // no auth mongodb://localhost:23456/test
            // auth mongodb://matt:xxx@localhost:23456/test?authSource=admin&retryWrites=false
            // OrclAPI mongodb://matt:xxx@127.0.0.1:27017/matt?authMechanism=PLAIN&authSource=$external&retryWrites=false&loadBalanced=true&tls=true&tlsAllowInvalidCertificates=true
            uri = uri.trim();

            String databaseName = null;
            try {
                ConnectionString cs = new ConnectionString(uri);
                databaseName = cs.getDatabase();
            } catch (Exception e) {
                System.err.println("Could not parse connection string: " + e.getMessage());
                e.printStackTrace();
                return null;
            }

            if (databaseName == null || databaseName.isEmpty()) {
                System.err.println("No database name found in URI. Ensure the URI ends with /yourDbName");
                return null;
            }

            return new String[]{uri, databaseName};

        } catch (IOException e) {
            System.err.println("Error reading mongoConn.txt: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    // check permissions on databases/collections for user
    private static void checkAccessibleDatabasesAndCollections(MongoClient mongoClient) {
        System.out.println("\n=== Checking accessible databases and collections for current user ===");
        try {
            MongoIterable<String> dbNames = mongoClient.listDatabaseNames();
            for (String dbName : dbNames) {
                System.out.println("Database: " + dbName);
                try {
                    MongoDatabase tmpDb = mongoClient.getDatabase(dbName);
                    for (String collName : tmpDb.listCollectionNames()) {
                        System.out.println("   Collection: " + collName);
                    }
                } catch (Exception e) {
                    System.out.println("   (No access to list collections in " + dbName + ")");
                }
            }
        } catch (Exception e) {
            System.out.println("Unable to list databases. User may not have listDatabases privilege.");
            System.out.println("Continuing...");
        }
    }

    // check roles/privileges for user
    private static void checkUserRolesAndPrivileges(MongoDatabase db) {
        System.out.println("\n=== Checking roles and privileges for current user ===");
        try {
            Document connStatus = db.runCommand(new Document("connectionStatus", 1));
            Document authInfo = (Document) connStatus.get("authInfo");
            if (authInfo != null) {
                Object usersObj = authInfo.get("authenticatedUsers");
                if (usersObj instanceof List<?>) {
                    System.out.println("Authenticated Users:");
                    for (Object u : (List<?>) usersObj) {
                        if (u instanceof Document) {
                            Document userDoc = (Document) u;
                            System.out.println("  " + userDoc.toJson());
                        }
                    }
                }

                Object rolesObj = authInfo.get("authenticatedUserRoles");
                if (rolesObj instanceof List<?>) {
                    System.out.println("\nAuthenticated User Roles:");
                    for (Object r : (List<?>) rolesObj) {
                        if (r instanceof Document) {
                            Document roleDoc = (Document) r;
                            String role = roleDoc.getString("role");
                            String roleDb = roleDoc.getString("db");
                            System.out.printf("  Role: %-20s | Database Scope: %s%n", role, roleDb);
                        }
                    }
                }
            } else {
                System.out.println("No authInfo returned. User may not be authenticated.");
            }
        } catch (Exception e) {
            System.out.println("Unable to run connectionStatus command: " + e.getMessage());
            System.out.println("Continuing without role/privilege info...");
        }
    }

    // show role info
    private static void showRolesInfo(MongoClient client, MongoDatabase currentDb) {
        System.out.println("\n=== Detailed Roles Info (rolesInfo command) ===");

        java.util.function.Consumer<Document> printRoles = (result) -> {
            Object rolesObj = result.get("roles");
            if (rolesObj instanceof List<?>) {
                for (Object r : (List<?>) rolesObj) {
                    if (r instanceof Document) {
                        Document roleDoc = (Document) r;
                        String role = roleDoc.getString("role");
                        String roleDb = roleDoc.getString("db");
                        System.out.println("\nRole: " + role + " | DB: " + roleDb);

                        System.out.printf("  %-30s | %-50s%n", "Resource", "Actions");
                        System.out.println("  " + "-".repeat(30) + "-+-" + "-".repeat(50));

                        Object privsObj = roleDoc.get("privileges");
                        if (privsObj instanceof List<?>) {
                            for (Object p : (List<?>) privsObj) {
                                if (p instanceof Document) {
                                    Document privDoc = (Document) p;

                                    StringBuilder resourceDesc = new StringBuilder();
                                    Document resource = (Document) privDoc.get("resource");
                                    if (resource != null) {
                                        if (resource.containsKey("cluster") && resource.getBoolean("cluster", false)) {
                                            resourceDesc.append("cluster");
                                        } else {
                                            String dbName = resource.getString("db");
                                            String collName = resource.getString("collection");
                                            resourceDesc.append("db=")
                                                    .append(dbName != null ? dbName : "*");
                                            resourceDesc.append(", coll=")
                                                    .append(collName != null ? collName : "*");
                                        }
                                    }

                                    // skip warnings
                                    @SuppressWarnings("unchecked")
                                    List<String> actions = (List<String>) privDoc.get("actions");
                                    String actionsStr = (actions != null) ? String.join(", ", actions) : "";

                                    System.out.printf("  %-30s | %-50s%n", resourceDesc, actionsStr);
                                }
                            }
                        }
                    }
                }
            }
        };

        try {
            MongoDatabase adminDb = client.getDatabase("admin");
            Document cmd = new Document("rolesInfo", 1).append("showPrivileges", true);
            Document result = adminDb.runCommand(cmd);

            Object rolesObj = result.get("roles");
            if (rolesObj instanceof List && !((List<?>) rolesObj).isEmpty()) {
                System.out.println("[Source: admin]");
                printRoles.accept(result);
                return;
            }

            // fallback to current DB
            System.out.println("[No roles found in admin. Trying current DB...]");
            Document fallbackResult = currentDb.runCommand(cmd);
            printRoles.accept(fallbackResult);

        } catch (Exception e) {
            System.out.println("Unable to run rolesInfo command: " + e.getMessage());
            System.out.println("Continuing without detailed roles info...");
        }
    }

    // main app logic
    private static void runApp(MongoDatabase database, MongoClient mongoClient) {
        Scanner scanner = new Scanner(System.in);
        while (true) {
            printSeparator();
            System.out.println("Choose an operation:");
            System.out.println("1. Add new registrant");
            System.out.println("2. Update a registrant");
            System.out.println("3. Query registrant by email");
            System.out.println("4. Query email domains with counts (Descending Order)");
            System.out.println("5. Show detailed rolesInfo");
            System.out.println("6. Exit");
            System.out.print("Enter your choice: ");

            int choice = scanner.nextInt();
            scanner.nextLine(); // get the section

            switch (choice) {
                case 1 -> addRegistrantPrompt(scanner, database);
                case 2 -> updateRegistrantPrompt(scanner, database);
                case 3 -> queryRegistrantByEmailPrompt(scanner, database);
                case 4 -> queryEmailDomains(database);
                case 5 -> showRolesInfo(mongoClient, database);
                case 6 -> {
                    System.out.println("Exiting program. Goodbye.");
                    mongoClient.close();
                    scanner.close();
                    return;
                }
                default -> System.out.println("Invalid choice. Please try again.");
            }
        }
    }

    // add a user
    private static void addRegistrantPrompt(Scanner scanner, MongoDatabase database) {
        MongoCollection<Document> collection = database.getCollection("registrations");

        printSeparator();
        String name = getValidatedInput(scanner, "Enter name: ");
        int age = getIntInput(scanner, "Enter age: ");
        String city = getValidatedInput(scanner, "Enter city: ");
        String email = getValidatedInput(scanner, "Enter email: ");
        double lat = getDoubleInput(scanner, "Enter latitude: ");
        double lon = getDoubleInput(scanner, "Enter longitude: ");

        Document newRegistrant = new Document("name", name)
                .append("age", age)
                .append("city", city)
                .append("email", email)
                .append("location", new Document("type", "Point")
                        .append("coordinates", List.of(lon, lat)));

        collection.insertOne(newRegistrant);
        System.out.println("Added new registrant: " + name);
    }

    // update a user by email search
    private static void updateRegistrantPrompt(Scanner scanner, MongoDatabase database) {
        MongoCollection<Document> collection = database.getCollection("registrations");

        printSeparator();
        String email = getValidatedInput(scanner, "Enter the email address of the registrant to update: ");
        String notes = getValidatedInput(scanner, "Enter the notes to add: ");

        Document query = new Document("email", email);
        Document update = new Document("$set", new Document("notes", notes));

        try {
            collection.updateOne(query, update);
            System.out.println("Updated registrant with email: " + email);
        } catch (Exception e) {
            System.err.println("Failed to update registrant. " + e.getMessage());
            e.printStackTrace();
        }
    }

    // query user by email search
    private static void queryRegistrantByEmailPrompt(Scanner scanner, MongoDatabase database) {
        MongoCollection<Document> collection = database.getCollection("registrations");

        printSeparator();
        String email = getValidatedInput(scanner, "Enter the email address to query: ");

        Document query = new Document("email", email);
        Document registrant = collection.find(query).first();

        if (registrant != null) {
            System.out.println("\nRegistrant Details:");
            registrant.forEach((key, value) -> System.out.println(key + ": " + value));
        } else {
            System.out.println("No registrant found with the email address: " + email);
        }
    }

    // aggregrate email domains from email attribute
    private static void queryEmailDomains(MongoDatabase database) {
        MongoCollection<Document> collection = database.getCollection("registrations");

        printSeparator();
        System.out.println("Email Domains of Registrants with Counts (Descending Order):");
        Map<String, Integer> domainCounts = new HashMap<>();

        for (Document registrant : collection.find()) {
            String email = registrant.getString("email");
            if (email != null && email.contains("@")) {
                String domain = email.split("@")[1];
                domainCounts.put(domain, domainCounts.getOrDefault(domain, 0) + 1);
            }
        }

        domainCounts.entrySet().stream()
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                .forEach(entry -> System.out.println(entry.getKey() + ": " + entry.getValue()));

        long totalRegistrants = collection.countDocuments();
        System.out.println("\nTotal number of registrants: " + totalRegistrants);
    }

    // enure user inputs integers in menu
    private static int getIntInput(Scanner scanner, String prompt) {
        while (true) {
            System.out.print(prompt);
            try {
                return Integer.parseInt(scanner.nextLine());
            } catch (NumberFormatException e) {
                System.out.println("Invalid input. Please enter a valid integer.");
            }
        }
    }

    // handle double
    private static double getDoubleInput(Scanner scanner, String prompt) {
        while (true) {
            System.out.print(prompt);
            try {
                return Double.parseDouble(scanner.nextLine());
            } catch (NumberFormatException e) {
                System.out.println("Invalid input. Please enter a valid decimal number.");
            }
        }
    }

    // handle null input
    private static String getValidatedInput(Scanner scanner, String prompt) {
        while (true) {
            System.out.print(prompt);
            String input = scanner.nextLine().trim();
            if (!input.isEmpty()) {
                return input;
            }
            System.out.println("Input cannot be empty. Please try again.");
        }
    }

    // separator for readability 
    private static void printSeparator() {
        System.out.println("\n--------------------------");
    }
}
