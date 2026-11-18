import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.time.*;

public class FileManagementServer {
    private static final int PORT = 8088;
    private static final String STORAGE = "server_storage";
    private static final Map<String, User> users = new HashMap<>();
    private static final Map<String, String> sessions = new HashMap<>();
    private static final List<String> activityLog = new ArrayList<>();

    static class User {
        String username;
        String password;
        String role;
        List<String> files;

        User(String username, String password, String role) {
            this.username = username;
            this.password = password;
            this.role = role;
            this.files = new ArrayList<>();
        }
    }

    public static void main(String[] args) throws Exception {
        initialize();
        ServerSocket server = new ServerSocket(PORT);
        System.out.println("File server listening on " + PORT);

        while (true) {
            Socket client = server.accept();
            Thread worker = new Thread(() -> handleClient(client));
            worker.start();
        }
    }

    private static void initialize() throws IOException {
        Files.createDirectories(Paths.get(STORAGE));

        users.put("admin",
                new User("admin", "admin123", "ADMIN"));
        users.put("alice",
                new User("alice", "password1", "USER"));
        users.put("bob",
                new User("bob", "password2", "USER"));
    }

    private static void handleClient(Socket socket) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));
            PrintWriter writer = new PrintWriter(
                    socket.getOutputStream(), true);

            writer.println("Welcome to File Management Server");

            String username = null;

            while (true) {
                writer.println("Username:");
                username = reader.readLine();

                writer.println("Password:");
                String password = reader.readLine();

                if (authenticate(username, password)) {
                    break;
                }

                writer.println("Invalid credentials.");
            }

            String token = createSession(username);
            writer.println("Session token: " + token);

            while (true) {
                writer.println("COMMAND:");
                String command = reader.readLine();

                if (command == null || command.equalsIgnoreCase("EXIT")) {
                    break;
                }

                processCommand(command, username, reader, writer);
            }

            socket.close();
        } catch (Exception e) {
            System.out.println("Client error: " + e.getMessage());
        }
    }

    private static boolean authenticate(String username, String password) {
        User user = users.get(username);
        return user != null && user.password.equals(password);
    }

    private static String createSession(String username) {
        String token = username + "-" + System.currentTimeMillis();
        sessions.put(token, username);
        return token;
    }

    private static void processCommand(String command,
                                       String username,
                                       BufferedReader reader,
                                       PrintWriter writer)
            throws IOException {
        String[] parts = command.split(" ");
        String action = parts[0].toUpperCase();

        switch (action) {
            case "LIST":
                listFiles(username, writer);
                break;
            case "UPLOAD":
                uploadFile(username, reader, writer);
                break;
            case "DOWNLOAD":
                if (parts.length > 1) {
                    downloadFile(parts[1], writer);
                }
                break;
            case "DELETE":
                if (parts.length > 1) {
                    deleteFile(username, parts[1], writer);
                }
                break;
            case "RENAME":
                if (parts.length > 2) {
                    renameFile(parts[1], parts[2], writer);
                }
                break;
            case "INFO":
                if (parts.length > 1) {
                    fileInfo(parts[1], writer);
                }
                break;
            case "SEARCH":
                if (parts.length > 1) {
                    searchFiles(parts[1], writer);
                }
                break;
            case "SHARE":
                if (parts.length > 2) {
                    shareFile(parts[1], parts[2], writer);
                }
                break;
            case "LOG":
                showLogs(username, writer);
                break;
            case "ADMIN":
                adminCommand(username, parts, writer);
                break;
            default:
                writer.println("Unknown command.");
        }
    }

    private static Path getStoragePath(String filename) {
        return Paths.get(STORAGE, filename);
    }

    private static void listFiles(String username, PrintWriter writer)
            throws IOException {
        File directory = new File(STORAGE);

        File[] files = directory.listFiles();

        if (files == null) {
            writer.println("No files.");
            return;
        }

        for (File file : files) {
            writer.println(file.getName());
        }
    }

    private static void uploadFile(String username,
                                   BufferedReader reader,
                                   PrintWriter writer)
            throws IOException {
        writer.println("Filename:");
        String filename = reader.readLine();

        writer.println("Content:");
        String content = reader.readLine();

        Path path = getStoragePath(filename);
        Files.write(path, content.getBytes());

        users.get(username).files.add(filename);
        log(username + " uploaded " + filename);

        writer.println("Upload complete.");
    }

    private static void downloadFile(String filename,
                                     PrintWriter writer)
            throws IOException {
        Path path = getStoragePath(filename);

        if (!Files.exists(path)) {
            writer.println("File not found.");
            return;
        }

        String content = new String(Files.readAllBytes(path));
        writer.println(content);
    }

    private static void deleteFile(String username,
                                   String filename,
                                   PrintWriter writer)
            throws IOException {
        Path path = getStoragePath(filename);

        if (!Files.exists(path)) {
            writer.println("File not found.");
            return;
        }

        Files.delete(path);
        users.get(username).files.remove(filename);

        log(username + " deleted " + filename);
        writer.println("File deleted.");
    }

    private static void renameFile(String oldName,
                                   String newName,
                                   PrintWriter writer)
            throws IOException {
        Path oldPath = getStoragePath(oldName);
        Path newPath = getStoragePath(newName);

        Files.move(oldPath, newPath);
        writer.println("File renamed.");
    }

    private static void fileInfo(String filename,
                                 PrintWriter writer)
            throws IOException {
        Path path = getStoragePath(filename);

        if (!Files.exists(path)) {
            writer.println("File not found.");
            return;
        }

        writer.println("Name: " + path.getFileName());
        writer.println("Size: " + Files.size(path));
        writer.println("Modified: "
                + Files.getLastModifiedTime(path));
    }

    private static void searchFiles(String query,
                                    PrintWriter writer) {
        File directory = new File(STORAGE);
        File[] files = directory.listFiles();

        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.getName().contains(query)) {
                writer.println(file.getName());
            }
        }
    }

    private static void shareFile(String filename,
                                  String targetUser,
                                  PrintWriter writer) {
        User user = users.get(targetUser);

        if (user == null) {
            writer.println("User not found.");
            return;
        }

        user.files.add(filename);
        writer.println("File shared.");
    }

    private static void showLogs(String username,
                                 PrintWriter writer) {
        for (String entry : activityLog) {
            writer.println(entry);
        }
    }

    private static void adminCommand(String username,
                                     String[] parts,
                                     PrintWriter writer) {
        User user = users.get(username);

        if (!user.role.equals("ADMIN")) {
            writer.println("Access denied.");
            return;
        }

        if (parts.length < 2) {
            writer.println("Missing admin command.");
            return;
        }

        switch (parts[1].toUpperCase()) {
            case "USERS":
                for (User account : users.values()) {
                    writer.println(account.username + " "
                            + account.role);
                }
                break;
            case "ADDUSER":
                if (parts.length >= 5) {
                    users.put(parts[2],
                            new User(parts[2], parts[3], parts[4]));
                    writer.println("User created.");
                }
                break;
            case "DELETEUSER":
                if (parts.length >= 3) {
                    users.remove(parts[2]);
                    writer.println("User deleted.");
                }
                break;
            case "ROLE":
                if (parts.length >= 4) {
                    User target = users.get(parts[2]);
                    if (target != null) {
                        target.role = parts[3];
                    }
                }
                break;
            default:
                writer.println("Unknown admin command.");
        }
    }

    private static void log(String event) {
        activityLog.add(LocalDateTime.now() + " " + event);
    }

    private static void exportLogs() throws IOException {
        Files.write(Paths.get("server.log"), activityLog);
    }

    private static void importUsers() throws IOException {
        Path path = Paths.get("users.txt");

        if (!Files.exists(path)) {
            return;
        }

        for (String line : Files.readAllLines(path)) {
            String[] parts = line.split(":");

            if (parts.length >= 3) {
                users.put(parts[0],
                        new User(parts[0], parts[1], parts[2]));
            }
        }
    }

    private static void saveUsers() throws IOException {
        List<String> lines = new ArrayList<>();

        for (User user : users.values()) {
            lines.add(user.username + ":"
                    + user.password + ":"
                    + user.role);
        }

        Files.write(Paths.get("users.txt"), lines);
    }

    private static void createDirectory(String name)
            throws IOException {
        Files.createDirectories(getStoragePath(name));
    }

    private static void copyFile(String source,
                                 String destination)
            throws IOException {
        Files.copy(getStoragePath(source),
                getStoragePath(destination),
                StandardCopyOption.REPLACE_EXISTING);
    }

    private static void moveFile(String source,
                                 String destination)
            throws IOException {
        Files.move(getStoragePath(source),
                getStoragePath(destination));
    }

    private static void changePassword(String username,
                                       String password) {
        User user = users.get(username);

        if (user != null) {
            user.password = password;
        }
    }

    private static void addUser(String username,
                                String password,
                                String role) {
        users.put(username, new User(username, password, role));
    }

    private static void removeUser(String username) {
        users.remove(username);
    }

    private static void listUsers(PrintWriter writer) {
        for (User user : users.values()) {
            writer.println(user.username);
        }
    }

    private static void clearLogs() {
        activityLog.clear();
    }

    private static void systemStatus(PrintWriter writer) {
        writer.println("Users: " + users.size());
        writer.println("Sessions: " + sessions.size());
        writer.println("Files: "
                + new File(STORAGE).list().length);
        writer.println("Time: " + LocalDateTime.now());
    }

    private static void backupStorage() throws IOException {
        Path source = Paths.get(STORAGE);
        Path backup = Paths.get("storage_backup");

        Files.createDirectories(backup);

        try (var stream = Files.list(source)) {
            stream.forEach(path -> {
                try {
                    Files.copy(path,
                            backup.resolve(path.getFileName()),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private static void restoreStorage() throws IOException {
        Path source = Paths.get("storage_backup");
        Path destination = Paths.get(STORAGE);

        if (!Files.exists(source)) {
            return;
        }

        try (var stream = Files.list(source)) {
            stream.forEach(path -> {
                try {
                    Files.copy(path,
                            destination.resolve(path.getFileName()),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private static void generateReport(PrintWriter writer) {
        writer.println("Server Report");
        writer.println("Generated: " + LocalDateTime.now());
        writer.println("Registered users: " + users.size());
        writer.println("Active sessions: " + sessions.size());
        writer.println("Activity entries: " + activityLog.size());
    }

    private static void cleanupSessions() {
        sessions.clear();
    }

    private static void disconnectAll() {
        cleanupSessions();
    }

    private static void printHelp(PrintWriter writer) {
        writer.println("LIST");
        writer.println("UPLOAD");
        writer.println("DOWNLOAD filename");
        writer.println("DELETE filename");
        writer.println("RENAME old new");
        writer.println("INFO filename");
        writer.println("SEARCH query");
        writer.println("SHARE filename username");
        writer.println("LOG");
        writer.println("ADMIN command");
        writer.println("EXIT");
    }
}
