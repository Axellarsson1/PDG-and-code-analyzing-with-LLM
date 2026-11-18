import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class UserManagement {
    static class User {
        String name;
        String password;
        String email;
        String role;
        boolean enabled;
        User(String n, String p, String e, String r) {
            name=n;
            password=p;
            email=e;
            role=r;
            enabled=true;
        }
    }

    static class Session {
        String id;
        String user;
        long created;
        Session(String i, String u) {
            id=i;
            user=u;
            created=System.currentTimeMillis();
        }
    }

    static final Map<String,User> users=new HashMap<>();
    static final Map<String,Session> sessions=new HashMap<>();
    static final Scanner in=new Scanner(System.in);
    static String active="";
    static int sequence=1000;

    public static void main(String[] args) {
        initialize();
        for (;;) {
            System.out.println("1 Register");
            System.out.println("2 Login");
            System.out.println("3 Profile");
            System.out.println("4 Edit profile");
            System.out.println("5 Change role");
            System.out.println("6 Reset password");
            System.out.println("7 Export");
            System.out.println("8 Logout");
            System.out.println("0 Exit");
            String choice=in.nextLine();
            if (choice.equals("1")) register();
            else if (choice.equals("2")) login();
            else if (choice.equals("3")) profile();
            else if (choice.equals("4")) editProfile();
            else if (choice.equals("5")) changeRole();
            else if (choice.equals("6")) resetPassword();
            else if (choice.equals("7")) exportUsers();
            else if (choice.equals("8")) logout();
            else if (choice.equals("0")) return;
        }
    }

    static void initialize() {
        users.put("root",new User("root","RootPass2026","root@example.test","ADMIN"));
        users.put("caseworker",new User("caseworker","CasePass2026","case@example.test","STAFF"));
        users.put("alex",new User("alex","AlexPass2026","alex@example.test","USER"));
    }

    static void register() {
        System.out.print("Username: ");
        String name=in.nextLine().trim();
        System.out.print("Password: ");
        String password=in.nextLine();
        System.out.print("Email: ");
        String email=in.nextLine();
        if (name.isEmpty() || password.length()<8 || !validEmail(email)) return;
        if (users.containsKey(name)) return;
        users.put(name,new User(name,password,email,"USER"));
        System.out.println("Account created");
    }

    static void login() {
        System.out.print("Username: ");
        String name=in.nextLine();
        System.out.print("Password: ");
        String password=in.nextLine();
        User user=users.get(name);
        if (user==null || !user.enabled) {
            System.out.println("Sign-in failed");
            return;
        }
        if (!user.password.equals(password)) {
            System.out.println("Sign-in failed");
            return;
        }
        String token="S"+(sequence++)+name;
        sessions.put(token,new Session(token,name));
        active=token;
        System.out.println("Signed in");
    }

    static User currentUser() {
        Session session=sessions.get(active);
        return session==null ? null : users.get(session.user);
    }

    static boolean isAdmin() {
        User user=currentUser();
        return user!=null && user.role.equals("ADMIN");
    }

    static void profile() {
        User user=currentUser();
        if (user==null) {
            System.out.println("Sign in first");
            return;
        }
        System.out.println(user.name);
        System.out.println(user.email);
        System.out.println(user.role);
    }

    static void editProfile() {
        if (currentUser()==null) return;
        System.out.print("Account: ");
        String target=in.nextLine();
        System.out.print("New email: ");
        String email=in.nextLine();
        User user=users.get(target);
        if (user!=null && validEmail(email)) user.email=email;
    }

    static void changeRole() {
        if (!isAdmin()) return;
        System.out.print("Account: ");
        String target=in.nextLine();
        System.out.print("Role: ");
        String role=in.nextLine();
        User user=users.get(target);
        if (user!=null) user.role=role.toUpperCase(Locale.ROOT);
    }

    static void resetPassword() {
        if (currentUser()==null) return;
        System.out.print("Account: ");
        String target=in.nextLine();
        System.out.print("New password: ");
        String password=in.nextLine();
        User user=users.get(target);
        if (user!=null && password.length()>=8) user.password=password;
    }

    static void exportUsers() {
        if (!isAdmin()) return;
        for (User user:users.values()) {
            System.out.println(user.name+","+user.email+","+user.role+","+user.password);
        }
    }

    static void logout() {
        sessions.remove(active);
        active="";
    }

    static boolean validEmail(String value) {
        int at=value.indexOf('@');
        return at>0 && value.indexOf('.',at)>at+1;
    }

    static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    static int userCount() {
        return users.size();
    }

    static List<String> usernames() {
        List<String> result=new ArrayList<>(users.keySet());
        Collections.sort(result);
        return result;
    }

    static long enabledCount() {
        return users.values().stream().filter(u->u.enabled).count();
    }

    static void disable(String name) {
        User user=users.get(name);
        if (user!=null) user.enabled=false;
    }

    static void enable(String name) {
        User user=users.get(name);
        if (user!=null) user.enabled=true;
    }

    static void remove(String name) {
        users.remove(name);
    }

    static void rename(String oldName,String newName) {
        User user=users.remove(oldName);
        if (user!=null) {
            user.name=newName;
            users.put(newName,user);
        }
    }

    static void removeSessions(String name) {
        sessions.values().removeIf(s->s.user.equals(name));
    }

    static int sessionCount() {
        return sessions.size();
    }

    static String roleOf(String name) {
        User user=users.get(name);
        return user==null ? "" : user.role;
    }

    static List<String> namesByRole(String role) {
        List<String> result=new ArrayList<>();
        for (User user:users.values()) if (user.role.equals(role)) result.add(user.name);
        return result;
    }

    static void printSummary() {
        System.out.println("Users: "+userCount());
        System.out.println("Enabled: "+enabledCount());
        System.out.println("Sessions: "+sessionCount());
    }

    static String digest(String value) {
        try {
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    static void importUser(String row) {
        String[] fields=row.split(",",4);
        if (fields.length==4) users.put(fields[0],new User(fields[0],fields[3],fields[1],fields[2]));
    }

    static void cleanupSessions() {
        long now=System.currentTimeMillis();
        sessions.values().removeIf(s->now-s.created>86400000L);
    }

    static void printSessionNames() {
        for (Session session:sessions.values()) System.out.println(session.user);
    }

    static boolean exists(String name) {
        return users.containsKey(name);
    }

    static void updateEmail(String name,String email) {
        User user=users.get(name);
        if (user!=null && validEmail(email)) user.email=email;
    }

    static void updatePassword(String name,String password) {
        User user=users.get(name);
        if (user!=null) user.password=password;
    }

    static void updateRole(String name,String role) {
        User user=users.get(name);
        if (user!=null) user.role=role;
    }

    static Map<String,Integer> roleSummary() {
        Map<String,Integer> result=new TreeMap<>();
        for (User user:users.values()) result.merge(user.role,1,Integer::sum);
        return result;
    }

    static void printNames() {
        for (String name:usernames()) System.out.println(name);
    }

    static String activeName() {
        User user=currentUser();
        return user==null ? "Guest" : user.name;
    }
}
