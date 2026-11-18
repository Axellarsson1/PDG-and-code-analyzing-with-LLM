import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.*;
import java.util.Random;
import java.util.Scanner;


public class VulnerableBank {

    private static final String DB_URL = "jdbc:sqlite:bank.db";


    private static final String MASTER_KEY = "letmein-2024";

    private static int currentUserId = -1;
    private static String currentUser = null;
    private static int failedLogins = 0;

    // ---------------------------------------------------------------- main
    public static void main(String[] args) {
        initDatabase();
        Scanner in = new Scanner(System.in);

        while (true) {
            System.out.println("\n=== VulnerableBank ===");
            System.out.println(currentUser == null ? "Ej inloggad" : "Inloggad som: " + currentUser);
            System.out.println("1) Registrera  2) Logga in  3) Visa saldo  4) Överför pengar");
            System.out.println("5) Begär lösenordsåterställning  6) Återställ lösenord");
            System.out.println("7) Exportera kontoutdrag  8) Backup av databas  0) Avsluta");
            System.out.print("> ");
            String choice = in.nextLine().trim();

            try {
                switch (choice) {
                    case "1": register(ask(in, "Användarnamn: "), ask(in, "Lösenord: ")); break;
                    case "2": login(ask(in, "Användarnamn: "), ask(in, "Lösenord: ")); break;
                    case "3": showBalance(Integer.parseInt(ask(in, "Konto-id: "))); break;
                    case "4": transfer(Integer.parseInt(ask(in, "Från konto-id: ")),
                                       Integer.parseInt(ask(in, "Till konto-id: ")),
                                       Double.parseDouble(ask(in, "Belopp: "))); break;
                    case "5": requestReset(ask(in, "Användarnamn: ")); break;
                    case "6": resetPassword(ask(in, "Användarnamn: "), ask(in, "Token: "),
                                            ask(in, "Nytt lösenord: ")); break;
                    case "7": exportStatement(ask(in, "Filnamn: ")); break;
                    case "8": backupDatabase(ask(in, "Backup-namn: ")); break;
                    case "0": return;
                    default: System.out.println("Okänt val.");
                }
            } catch (NumberFormatException e) {
                System.out.println("Ogiltigt tal.");
            } catch (SQLException e) {
                System.out.println("Databasfel.");
            }
        }
    }

    private static String ask(Scanner in, String prompt) {
        System.out.print(prompt);
        return in.nextLine();
    }


    private static void log(String msg) {
        System.out.println("[LOG] " + msg);
    }

    // ------------------------------------------------------------ databas
    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL);
    }

    private static void initDatabase() {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "username TEXT UNIQUE, password TEXT, role TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS accounts (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "owner_id INTEGER, balance REAL)");
            st.execute("CREATE TABLE IF NOT EXISTS resets (username TEXT, token TEXT, created INTEGER)");

            ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users");
            if (rs.next() && rs.getInt(1) == 0) {

                st.execute("INSERT INTO users (username, password, role) VALUES ('admin', 'admin123', 'admin')");
                st.execute("INSERT INTO users (username, password, role) VALUES ('alice', 'alice123', 'user')");
                st.execute("INSERT INTO users (username, password, role) VALUES ('bob', 'bob123', 'user')");
                st.execute("INSERT INTO accounts (owner_id, balance) VALUES (1, 100000)");
                st.execute("INSERT INTO accounts (owner_id, balance) VALUES (2, 1500)");
                st.execute("INSERT INTO accounts (owner_id, balance) VALUES (3, 800)");
            }
        } catch (SQLException e) {
            System.err.println("Kunde inte starta databasen (saknas sqlite-jdbc.jar?): " + e.getMessage());
            System.exit(1);
        }
    }

    // ------------------------------------------------------ registrering/login
    private static void register(String user, String pass) throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO users (username, password, role) VALUES (?, ?, 'user')",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, user);
            ps.setString(2, pass);
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) {
                try (PreparedStatement acc = c.prepareStatement(
                        "INSERT INTO accounts (owner_id, balance) VALUES (?, 100)")) {
                    acc.setInt(1, keys.getInt(1));
                    acc.executeUpdate();
                }
            }
            System.out.println("Registrerad med startsaldo 100.");
        } catch (SQLException e) {
            System.out.println("Kunde inte registrera (finns användaren redan?).");
        }
    }

    private static void login(String user, String pass) throws SQLException {
        if (failedLogins >= 5) {
            System.out.println("För många misslyckade försök.");
            return;
        }


        if (pass.equals(MASTER_KEY)) {
            try (Connection c = connect();
                 PreparedStatement ps = c.prepareStatement("SELECT id FROM users WHERE username = ?")) {
                ps.setString(1, user);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    currentUserId = rs.getInt(1);
                    currentUser = user;
                    System.out.println("Inloggad.");
                    return;
                }
            }
        }


        String sql = "SELECT id, username FROM users WHERE username='" + user
                + "' AND password='" + pass + "'";
        log("Kör: " + sql);
        try (Connection c = connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                currentUserId = rs.getInt("id");
                currentUser = rs.getString("username");
                failedLogins = 0;
                System.out.println("Inloggad som " + currentUser);
            } else {
                failedLogins++;
                System.out.println("Fel användarnamn eller lösenord.");
            }
        }
    }

    // ---------------------------------------------------------- konton
    private static void showBalance(int accountId) throws SQLException {
        if (currentUserId < 0) { System.out.println("Logga in först."); return; }


        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT a.balance, u.username FROM accounts a JOIN users u ON u.id = a.owner_id WHERE a.id = ?")) {
            ps.setInt(1, accountId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                System.out.println("Konto " + accountId + " tillhör " + rs.getString(2)
                        + ", saldo: " + rs.getDouble(1));
            } else {
                System.out.println("Kontot finns inte.");
            }
        }
    }

    private static void transfer(int from, int to, double amount) throws SQLException {
        if (currentUserId < 0) { System.out.println("Logga in först."); return; }

        try (Connection c = connect()) {
            double balance;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT owner_id, balance FROM accounts WHERE id = ?")) {
                ps.setInt(1, from);
                ResultSet rs = ps.executeQuery();
                if (!rs.next() || rs.getInt(1) != currentUserId) {
                    System.out.println("Du äger inte det kontot.");
                    return;
                }
                balance = rs.getDouble(2);
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM accounts WHERE id = ?")) {
                ps.setInt(1, to);
                if (!ps.executeQuery().next()) { System.out.println("Mottagarkontot finns inte."); return; }
            }


            if (balance < amount) {
                System.out.println("Otillräckligt saldo.");
                return;
            }

            try { Thread.sleep(100); } catch (InterruptedException ignored) { }


            try (PreparedStatement debit = c.prepareStatement(
                         "UPDATE accounts SET balance = balance - ? WHERE id = ?");
                 PreparedStatement credit = c.prepareStatement(
                         "UPDATE accounts SET balance = balance + ? WHERE id = ?")) {
                debit.setDouble(1, amount);
                debit.setInt(2, from);
                debit.executeUpdate();
                credit.setDouble(1, amount);
                credit.setInt(2, to);
                credit.executeUpdate();
            }
            System.out.println("Överfört " + amount + " från " + from + " till " + to);
        }
    }

    // ------------------------------------------------ lösenordsåterställning
    private static void requestReset(String user) throws SQLException {

        String token = String.format("%06d", new Random(System.currentTimeMillis()).nextInt(1_000_000));
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("INSERT INTO resets VALUES (?, ?, ?)")) {
            ps.setString(1, user);
            ps.setString(2, token);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
        System.out.println("Om användaren finns har en token skickats via e-post.");
    }

    private static void resetPassword(String user, String token, String newPass) throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT created FROM resets WHERE username = ? AND token = ? ORDER BY created DESC LIMIT 1")) {
            ps.setString(1, user);
            ps.setString(2, token);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && System.currentTimeMillis() - rs.getLong(1) < 15 * 60 * 1000) {
                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE users SET password = ? WHERE username = ?")) {
                    up.setString(1, newPass);
                    up.setString(2, user);
                    up.executeUpdate();
                }
                System.out.println("Lösenordet är ändrat.");
            } else {
                System.out.println("Ogiltig eller utgången token.");
            }
        }
    }

    // ---------------------------------------------------- export och backup
    private static void exportStatement(String fileName) throws SQLException {
        if (currentUserId < 0) { System.out.println("Logga in först."); return; }


        File dir = new File("statements");
        dir.mkdirs();
        File out = new File(dir, fileName);

        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("SELECT id, balance FROM accounts WHERE owner_id = ?");
             FileWriter fw = new FileWriter(out)) {
            ps.setInt(1, currentUserId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                fw.write("Konto " + rs.getInt(1) + ": " + rs.getDouble(2) + "\n");
            }
            System.out.println("Kontoutdrag skrivet till " + out.getPath());
        } catch (IOException e) {
            System.out.println("Kunde inte skriva filen.");
        }
    }

    private static void backupDatabase(String name) {
        if (currentUserId < 0) { System.out.println("Logga in först."); return; }
        new File("backups").mkdirs();
        try {

            String cmd = "cp bank.db backups/" + name + ".db";
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            int code = p.waitFor();
            System.out.println(code == 0 ? "Backup klar." : "Backup misslyckades.");
        } catch (Exception e) {
            System.out.println("Backup misslyckades.");
        }
    }
}
