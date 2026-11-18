import javax.imageio.ImageIO;
import javax.net.ssl.*;
import javax.swing.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.sql.*;
import java.util.Random;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;


public class VulnerableCatchGame extends JFrame {


    private static final String ADMIN_PASSWORD = "admin123";
    private static final String API_KEY = "sk_live_51H8xExampleHardcodedKey";
    private static final String DB_URL = "jdbc:sqlite:game.db";

    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);
    private GamePanel gamePanel;
    private String currentUser;
    private String sessionToken;

    // ------------------------------------------------------------------ main
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new VulnerableCatchGame().setVisible(true));
    }

    public VulnerableCatchGame() {
        super("Catch the Dot");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(640, 520);
        setLocationRelativeTo(null);

        initDatabase();

        gamePanel = new GamePanel();
        root.add(buildLoginPanel(), "login");
        root.add(buildGameScreen(), "game");
        add(root);
        cards.show(root, "login");
    }

    // ------------------------------------------------------------ databas
    private void initDatabase() {
        try (Connection c = DriverManager.getConnection(DB_URL);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS users (name TEXT PRIMARY KEY, pass TEXT)");
            st.execute("INSERT OR IGNORE INTO users VALUES ('admin', '" + md5(ADMIN_PASSWORD) + "')");
        } catch (SQLException e) {
            System.err.println("DB-fel (saknas sqlite-jdbc?): " + e.getMessage());
        }
    }


    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(s.getBytes("UTF-8"))) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean register(String user, String pass) {
        try (Connection c = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = c.prepareStatement("INSERT INTO users VALUES (?, ?)")) {
            ps.setString(1, user);
            ps.setString(2, md5(pass));
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private boolean login(String user, String pass) {

        System.out.println("Login attempt: user=" + user + " password=" + pass);


        String sql = "SELECT * FROM users WHERE name='" + user + "' AND pass='" + md5(pass) + "'";
        try (Connection c = DriverManager.getConnection(DB_URL);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {

                sessionToken = Long.toHexString(new Random(System.currentTimeMillis()).nextLong());
                return true;
            }
        } catch (SQLException e) {
            System.err.println("SQL-fel: " + e.getMessage());
        }
        return false;
    }

    // ------------------------------------------------------------------- GUI
    private JPanel buildLoginPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(6, 6, 6, 6);
        g.fill = GridBagConstraints.HORIZONTAL;

        JTextField userField = new JTextField(16);
        JPasswordField passField = new JPasswordField(16);
        JButton loginBtn = new JButton("Logga in");
        JButton regBtn = new JButton("Registrera");
        JLabel status = new JLabel(" ", SwingConstants.CENTER);

        g.gridx = 0; g.gridy = 0; p.add(new JLabel("Användare:"), g);
        g.gridx = 1; p.add(userField, g);
        g.gridx = 0; g.gridy = 1; p.add(new JLabel("Lösenord:"), g);
        g.gridx = 1; p.add(passField, g);
        g.gridx = 0; g.gridy = 2; p.add(loginBtn, g);
        g.gridx = 1; p.add(regBtn, g);
        g.gridx = 0; g.gridy = 3; g.gridwidth = 2; p.add(status, g);

        loginBtn.addActionListener(e -> {
            String u = userField.getText();
            if (login(u, new String(passField.getPassword()))) {
                currentUser = u;
                cards.show(root, "game");
                gamePanel.startGame();
            } else {
                status.setText("Fel användarnamn eller lösenord");
            }
        });
        regBtn.addActionListener(e -> status.setText(
                register(userField.getText(), new String(passField.getPassword()))
                        ? "Registrerad!" : "Kunde inte registrera"));
        return p;
    }

    private JPanel buildGameScreen() {
        JPanel screen = new JPanel(new BorderLayout());
        screen.add(gamePanel, BorderLayout.CENTER);

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 6));
        JButton newGame = new JButton("Nytt spel");
        JButton save = new JButton("Spara");
        JButton load = new JButton("Ladda");
        JButton bg = new JButton("Bakgrund");
        JButton level = new JButton("Nivå (XML)");
        JButton export = new JButton("Exportera");
        JButton upload = new JButton("Ladda upp poäng");
        for (JButton b : new JButton[]{newGame, save, load, bg, level, export, upload}) bar.add(b);
        screen.add(bar, BorderLayout.SOUTH);

        newGame.addActionListener(e -> gamePanel.startGame());
        save.addActionListener(e -> saveGame());
        load.addActionListener(e -> {
            String name = JOptionPane.showInputDialog(this, "Filnamn på sparfil:");
            if (name != null) loadGame(name);
        });
        bg.addActionListener(e -> {
            String name = JOptionPane.showInputDialog(this, "Bildfil (i mappen backgrounds):");
            if (name != null) loadBackground(name);
        });
        level.addActionListener(e -> {
            JTextArea area = new JTextArea("<level><speed>600</speed></level>", 8, 40);
            if (JOptionPane.showConfirmDialog(this, new JScrollPane(area), "Klistra in nivå-XML",
                    JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                loadLevel(area.getText());
            }
        });
        export.addActionListener(e -> exportScore());
        upload.addActionListener(e -> uploadScore());
        return screen;
    }

    // ----------------------------------------------------- spara / ladda
    static class SaveData implements Serializable {
        private static final long serialVersionUID = 1L;
        String user;
        int score;
    }

    private void saveGame() {
        new File("saves").mkdirs();
        SaveData d = new SaveData();
        d.user = currentUser;
        d.score = gamePanel.score;
        try (ObjectOutputStream out = new ObjectOutputStream(
                new FileOutputStream("saves/" + currentUser + ".sav"))) {
            out.writeObject(d);
            JOptionPane.showMessageDialog(this, "Sparat!");
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Kunde inte spara: " + ex.getMessage());
        }
    }

    private void loadGame(String fileName) {

        File f = new File("saves/" + fileName);

        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(f))) {
            SaveData d = (SaveData) in.readObject();
            gamePanel.score = d.score;
            gamePanel.repaint();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Kunde inte ladda: " + ex.getMessage());
        }
    }

    private void loadBackground(String fileName) {
        try {
            BufferedImage img = ImageIO.read(new File("backgrounds/" + fileName));
            gamePanel.background = img;
            gamePanel.repaint();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Kunde inte ladda bild: " + ex.getMessage());
        }
    }

    // ----------------------------------------------------------- nivå (XML)
    private void loadLevel(String xml) {
        try {

            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new InputSource(new StringReader(xml)));
            String speed = doc.getElementsByTagName("speed").item(0).getTextContent().trim();
            gamePanel.moveDelayMs = Integer.parseInt(speed);
            JOptionPane.showMessageDialog(this, "Hastighet satt till " + speed + " ms");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Ogiltig nivå: " + ex.getMessage());
        }
    }

    // -------------------------------------------------------------- export
    private void exportScore() {
        try {

            String cmd = "echo " + currentUser + " " + gamePanel.score + " >> export.txt";
            boolean win = System.getProperty("os.name").toLowerCase().contains("win");
            String[] full = win ? new String[]{"cmd", "/c", cmd} : new String[]{"sh", "-c", cmd};
            Runtime.getRuntime().exec(full).waitFor();
            JOptionPane.showMessageDialog(this, "Exporterat till export.txt");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Export misslyckades: " + ex.getMessage());
        }
    }

    // -------------------------------------------------------------- upload
    private void uploadScore() {
        try {

            TrustManager[] trustAll = {new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) { }
                public void checkServerTrusted(X509Certificate[] c, String a) { }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);

            HttpsURLConnection con = (HttpsURLConnection)
                    new URL("https://example.com/api/score").openConnection();
            con.setRequestMethod("POST");
            con.setRequestProperty("Authorization", "Bearer " + API_KEY);
            con.setRequestProperty("X-Session", sessionToken);
            con.setDoOutput(true);
            try (OutputStream os = con.getOutputStream()) {
                os.write(("user=" + currentUser + "&score=" + gamePanel.score).getBytes("UTF-8"));
            }
            JOptionPane.showMessageDialog(this, "Server svarade: " + con.getResponseCode());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Uppladdning misslyckades: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ spelpanel
    static class GamePanel extends JPanel {
        int score = 0;
        int timeLeft = 30;
        int moveDelayMs = 700;
        BufferedImage background;

        private int dotX = 100, dotY = 100;
        private static final int R = 22;
        private final Random rnd = new Random();
        private Timer moveTimer, clockTimer;

        GamePanel() {
            setBackground(new Color(30, 30, 40));
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (timeLeft > 0 && e.getPoint().distance(dotX, dotY) <= R) {
                        score++;
                        moveDot();
                    }
                }
            });
        }

        void startGame() {
            score = 0;
            timeLeft = 30;
            if (moveTimer != null) moveTimer.stop();
            if (clockTimer != null) clockTimer.stop();
            moveTimer = new Timer(moveDelayMs, e -> moveDot());
            clockTimer = new Timer(1000, e -> {
                if (--timeLeft <= 0) {
                    moveTimer.stop();
                    clockTimer.stop();
                }
                repaint();
            });
            moveTimer.start();
            clockTimer.start();
            moveDot();
        }

        private void moveDot() {
            dotX = R + rnd.nextInt(Math.max(1, getWidth() - 2 * R));
            dotY = R + rnd.nextInt(Math.max(1, getHeight() - 2 * R));
            repaint();
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (background != null) g2.drawImage(background, 0, 0, getWidth(), getHeight(), null);
            if (timeLeft > 0) {
                g2.setColor(Color.RED);
                g2.fillOval(dotX - R, dotY - R, 2 * R, 2 * R);
            }
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("SansSerif", Font.BOLD, 18));
            g2.drawString("Poäng: " + score + "    Tid: " + timeLeft, 12, 24);
            if (timeLeft <= 0) {
                g2.drawString("Game over! Slutpoäng: " + score, getWidth() / 2 - 120, getHeight() / 2);
            }
        }
    }
}
