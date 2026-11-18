package com.acme.notes;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

public class NotesServer {

    private static final String DB_URL = "jdbc:sqlite:notes.db";
    private static final String ADMIN_KEY = "adm-7f3a9c21-prod-key";
    private static final Path UPLOAD_ROOT = Paths.get("uploads");
    private static final Map<String, Integer> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<Integer, Preferences> PREFS = new ConcurrentHashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_BODY = 1_048_576;

    interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }

    static class Preferences implements Serializable {
        private static final long serialVersionUID = 1L;
        String theme = "light";
        String language = "en";
        int pageSize = 20;
    }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(UPLOAD_ROOT);
        initDatabase();

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/api/register", safe(NotesServer::handleRegister));
        server.createContext("/api/login", safe(NotesServer::handleLogin));
        server.createContext("/api/profile", safe(NotesServer::handleProfile));
        server.createContext("/api/notes/search", safe(NotesServer::handleSearch));
        server.createContext("/api/notes/import", safe(NotesServer::handleImport));
        server.createContext("/api/files", safe(NotesServer::handleDownload));
        server.createContext("/api/preview", safe(NotesServer::handlePreview));
        server.createContext("/api/preferences", safe(NotesServer::handlePreferences));
        server.createContext("/api/admin/ping", safe(NotesServer::handlePing));
        server.setExecutor(null);
        server.start();
        System.out.println("Notes server listening on port 8080");
    }

    private static HttpHandler safe(Handler handler) {
        return exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception e) {
                try {
                    send(exchange, 500, "{\"error\":\"internal error\"}", "application/json");
                } catch (IOException ignored) {
                }
            } finally {
                exchange.close();
            }
        };
    }

    private static void initDatabase() throws SQLException {
        try (Connection c = DriverManager.getConnection(DB_URL); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS users ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "username TEXT UNIQUE NOT NULL,"
                    + "email TEXT NOT NULL,"
                    + "password_hash TEXT NOT NULL,"
                    + "created_at TEXT DEFAULT CURRENT_TIMESTAMP)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS notes ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "owner_id INTEGER NOT NULL,"
                    + "title TEXT NOT NULL,"
                    + "content TEXT NOT NULL,"
                    + "created_at TEXT DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    private static void handleRegister(HttpExchange ex) throws Exception {
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, "{\"error\":\"method not allowed\"}", "application/json");
            return;
        }
        Map<String, String> form = parseForm(readBody(ex));
        String username = form.get("username");
        String email = form.get("email");
        String password = form.get("password");
        if (username == null || email == null || password == null
                || username.length() < 3 || password.length() < 8) {
            send(ex, 400, "{\"error\":\"invalid input\"}", "application/json");
            return;
        }
        try (Connection c = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO users (username, email, password_hash) VALUES (?, ?, ?)")) {
            ps.setString(1, username);
            ps.setString(2, email);
            ps.setString(3, hashPassword(password));
            ps.executeUpdate();
        } catch (SQLException e) {
            send(ex, 409, "{\"error\":\"user exists\"}", "application/json");
            return;
        }
        send(ex, 201, "{\"status\":\"created\"}", "application/json");
    }

    private static void handleLogin(HttpExchange ex) throws Exception {
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, "{\"error\":\"method not allowed\"}", "application/json");
            return;
        }
        Map<String, String> form = parseForm(readBody(ex));
        String username = form.getOrDefault("username", "");
        String password = form.getOrDefault("password", "");
        String query = "SELECT id FROM users WHERE username = '" + username
                + "' AND password_hash = '" + hashPassword(password) + "'";
        try (Connection c = DriverManager.getConnection(DB_URL);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(query)) {
            if (rs.next()) {
                String token = newToken();
                SESSIONS.put(token, rs.getInt("id"));
                send(ex, 200, "{\"token\":\"" + token + "\"}", "application/json");
            } else {
                send(ex, 401, "{\"error\":\"invalid credentials\"}", "application/json");
            }
        }
    }

    private static void handleProfile(HttpExchange ex) throws Exception {
        int userId = requireSession(ex);
        if (userId < 0) {
            return;
        }
        Map<String, String> params = parseForm(ex.getRequestURI().getRawQuery());
        int targetId = Integer.parseInt(params.getOrDefault("id", String.valueOf(userId)));
        try (Connection c = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT username, email, created_at FROM users WHERE id = ?")) {
            ps.setInt(1, targetId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    send(ex, 404, "{\"error\":\"not found\"}", "application/json");
                    return;
                }
                String body = "{\"username\":\"" + jsonEscape(rs.getString("username"))
                        + "\",\"email\":\"" + jsonEscape(rs.getString("email"))
                        + "\",\"createdAt\":\"" + jsonEscape(rs.getString("created_at")) + "\"}";
                send(ex, 200, body, "application/json");
            }
        }
    }

    private static void handleSearch(HttpExchange ex) throws Exception {
        int userId = requireSession(ex);
        if (userId < 0) {
            return;
        }
        Map<String, String> params = parseForm(ex.getRequestURI().getRawQuery());
        String q = params.getOrDefault("q", "");
        StringBuilder html = new StringBuilder();
        html.append("<html><body><h2>Results for ").append(q).append("</h2><ul>");
        try (Connection c = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT title FROM notes WHERE owner_id = ? AND title LIKE ?")) {
            ps.setInt(1, userId);
            ps.setString(2, "%" + q + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    html.append("<li>").append(htmlEscape(rs.getString("title"))).append("</li>");
                }
            }
        }
        html.append("</ul></body></html>");
        send(ex, 200, html.toString(), "text/html");
    }

    private static void handleImport(HttpExchange ex) throws Exception {
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, "{\"error\":\"method not allowed\"}", "application/json");
            return;
        }
        int userId = requireSession(ex);
        if (userId < 0) {
            return;
        }
        String xml = readBody(ex);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        NodeList nodes = doc.getElementsByTagName("note");
        List<String> imported = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO notes (owner_id, title, content) VALUES (?, ?, ?)")) {
            for (int i = 0; i < nodes.getLength(); i++) {
                Element note = (Element) nodes.item(i);
                String title = text(note, "title");
                String content = text(note, "content");
                ps.setInt(1, userId);
                ps.setString(2, title);
                ps.setString(3, content);
                ps.executeUpdate();
                imported.add("\"" + jsonEscape(title) + "\"");
            }
        }
        send(ex, 200, "{\"imported\":[" + String.join(",", imported) + "]}", "application/json");
    }

    private static void handleDownload(HttpExchange ex) throws Exception {
        int userId = requireSession(ex);
        if (userId < 0) {
            return;
        }
        Map<String, String> params = parseForm(ex.getRequestURI().getRawQuery());
        String name = params.get("name");
        if (name == null || name.isEmpty()) {
            send(ex, 400, "{\"error\":\"name required\"}", "application/json");
            return;
        }
        Path target = UPLOAD_ROOT.resolve(name);
        if (!Files.isRegularFile(target)) {
            send(ex, 404, "{\"error\":\"not found\"}", "application/json");
            return;
        }
        byte[] data = Files.readAllBytes(target);
        ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    private static void handlePreview(HttpExchange ex) throws Exception {
        int userId = requireSession(ex);
        if (userId < 0) {
            return;
        }
        Map<String, String> params = parseForm(ex.getRequestURI().getRawQuery());
        String target = params.get("url");
        if (target == null || !(target.startsWith("http://") || target.startsWith("https://"))) {
            send(ex, 400, "{\"error\":\"invalid url\"}", "application/json");
            return;
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        conn.setRequestMethod("GET");
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            char[] buf = new char[512];
            int n;
            while ((n = reader.read(buf)) > 0 && sb.length() < 4096) {
                sb.append(buf, 0, n);
            }
        }
        send(ex, 200, "{\"preview\":\"" + jsonEscape(sb.toString()) + "\"}", "application/json");
    }

    private static void handlePreferences(HttpExchange ex) throws Exception {
        int userId = requireSession(ex);
        if (userId < 0) {
            return;
        }
        byte[] raw = Base64.getDecoder().decode(readBody(ex).trim());
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(raw))) {
            Preferences restored = (Preferences) in.readObject();
            PREFS.put(userId, restored);
        }
        send(ex, 200, "{\"status\":\"restored\"}", "application/json");
    }

    private static void handlePing(HttpExchange ex) throws Exception {
        String key = ex.getRequestHeaders().getFirst("X-Admin-Key");
        if (key == null || !key.equals(ADMIN_KEY)) {
            send(ex, 403, "{\"error\":\"forbidden\"}", "application/json");
            return;
        }
        Map<String, String> params = parseForm(ex.getRequestURI().getRawQuery());
        String host = params.getOrDefault("host", "localhost");
        Process process = new ProcessBuilder("sh", "-c", "ping -c 1 " + host)
                .redirectErrorStream(true)
                .start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        process.waitFor();
        send(ex, 200, "{\"output\":\"" + jsonEscape(output.toString()) + "\"}", "application/json");
    }

    private static int requireSession(HttpExchange ex) throws IOException {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            Integer userId = SESSIONS.get(header.substring(7));
            if (userId != null) {
                return userId;
            }
        }
        send(ex, 401, "{\"error\":\"unauthorized\"}", "application/json");
        return -1;
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hashPassword(String password) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(password.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String text(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        return list.getLength() == 0 ? "" : list.item(0).getTextContent();
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] data = in.readNBytes(MAX_BODY);
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> parseForm(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        for (String pair : raw.split("&")) {
            int idx = pair.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }

    private static void send(HttpExchange ex, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String htmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
