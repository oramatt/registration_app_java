# MongoDBApp

A simple Java application that connects to a MongoDB (or Oracle Database API for MongoDB) instance, validates user access, and provides an interactive CLI for managing and querying a `registrations` collection.  

This project demonstrates:

- Connecting to MongoDB with **TLS / self-signed certificates** support.  
- Inspecting **user roles and privileges**.  
- Listing **databases and collections** accessible to the authenticated user.  
- Running CRUD operations against a `registrations` collection.  
- Performing **basic aggregations** (e.g., count email domains).  

---

## Features

- **TLS Support**: Honors the `tlsAllowInvalidCertificates=true` flag and can trust self-signed certs.  
- **User Access Check**: Prints all databases/collections the user can access.  
- **Role Inspection**: Uses `connectionStatus` and `rolesInfo` to show authenticated roles and their privileges.  
- **Interactive CLI**:  
  - Add a new registrant  
  - Update a registrant by email  
  - Query registrant details by email  
  - Aggregate email domains with counts  
  - Show role/privilege details  
- **Registrations Collection**: Supports fields like `name`, `age`, `city`, `email`, and geolocation (`Point`).

---

## Requirements

- Java 17+  
- MongoDB Java Driver 5.x (dependencies shown in `lib/`)  
  - `mongodb-driver-sync`  
  - `mongodb-driver-core`  
  - `bson`  
  - `bson-record-codec` (if needed)  
  - `slf4j-simple`  

---

## Setup

1. Clone the repository:

   ```bash
   git clone https://github.com/oramatt/MongoDBApp.git
   cd MongoDBApp
   ```

2. Compile the project:

   ```bash
   javac -cp ".:lib/*" MongoDBApp.java
   ```

3. Create a `mongoConn.txt` file in the same directory as the compiled class:

   Example connection strings:
   ```
   # Local MongoDB without auth
   mongodb://localhost:23456/test

   # MongoDB with auth
   mongodb://alice:password@localhost:23456/test?authSource=admin&retryWrites=false

   # Oracle Database API for MongoDB with TLS and external auth
   mongodb://matt:password@127.0.0.1:27017/matt?authMechanism=PLAIN&authSource=$external&retryWrites=false&loadBalanced=true&tls=true&tlsAllowInvalidCertificates=true
   ```

---

## Running

Run the program with:

```bash
java -cp ".:lib/*" MongoDBApp
```

It will:

1. Read the connection string and database from `mongoConn.txt`.  
2. Connect and print accessible databases and collections.  
3. Show authenticated user roles and privileges.  
4. Enter an interactive menu:

```
--------------------------
Choose an operation:
1. Add new registrant
2. Update a registrant
3. Query registrant by email
4. Query email domains with counts (Descending Order)
5. Show detailed rolesInfo
6. Exit
```

---

## Example: Add a Registrant

```
Enter name: John Doe
Enter age: 42
Enter city: New York
Enter email: john@example.com
Enter latitude: 40.7128
Enter longitude: -74.0060
```

Document inserted:

```json
{
  "name": "John Doe",
  "age": 42,
  "city": "New York",
  "email": "john@example.com",
  "location": {
    "type": "Point",
    "coordinates": [-74.0060, 40.7128]
  }
}
```

---

## Notes

- If you’re connecting with `tlsAllowInvalidCertificates=true`, the program will trust all TLS certs.  
- Make sure the `registrations` collection exists, or the app will create it automatically when you insert a document.  
- For Oracle Database API for MongoDB users, ensure the database is running with MongoDB API enabled.  

---

## License

This project is licensed under the **Universal Permissive License (UPL), Version 1.0**.  
See the [LICENSE](LICENSE) file for details.
