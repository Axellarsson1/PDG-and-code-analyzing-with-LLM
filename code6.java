import java.util.*;
import java.time.*;
import java.math.BigDecimal;
import java.io.*;
import java.security.MessageDigest;

public class BankingSimulator {
    private static Map<String, Account> accounts = new HashMap<>();
    private static Map<String, String> sessions = new HashMap<>();
    private static List<Transaction> transactions = new ArrayList<>();
    private static Scanner scanner = new Scanner(System.in);
    private static String currentUser;

    static class Account {
        String accountNumber;
        String owner;
        String pin;
        BigDecimal balance;
        String role;
        boolean frozen;
        List<String> history;

        Account(String accountNumber, String owner, String pin,
                BigDecimal balance, String role) {
            this.accountNumber = accountNumber;
            this.owner = owner;
            this.pin = pin;
            this.balance = balance;
            this.role = role;
            this.frozen = false;
            this.history = new ArrayList<>();
        }
    }

    static class Transaction {
        String id;
        String from;
        String to;
        BigDecimal amount;
        String timestamp;
        String description;

        Transaction(String from, String to, BigDecimal amount,
                    String description) {
            this.id = UUID.randomUUID().toString();
            this.from = from;
            this.to = to;
            this.amount = amount;
            this.description = description;
            this.timestamp = LocalDateTime.now().toString();
        }
    }

    public static void main(String[] args) {
        initialize();
        while (true) {
            if (currentUser == null) {
                showLoginMenu();
            } else {
                showAccountMenu();
            }
        }
    }

    private static void initialize() {
        accounts.put("100001",
                new Account("100001", "Alice", "1234",
                        new BigDecimal("5000.00"), "CUSTOMER"));
        accounts.put("100002",
                new Account("100002", "Bob", "5678",
                        new BigDecimal("3000.00"), "CUSTOMER"));
        accounts.put("900001",
                new Account("900001", "Administrator", "admin123",
                        new BigDecimal("10000.00"), "ADMIN"));
    }

    private static void showLoginMenu() {
        System.out.println("\n=== BANKING SIMULATOR ===");
        System.out.println("1. Login");
        System.out.println("2. Create account");
        System.out.println("3. Exit");
        System.out.print("Choice: ");

        String choice = scanner.nextLine();

        switch (choice) {
            case "1":
                login();
                break;
            case "2":
                createAccount();
                break;
            case "3":
                System.exit(0);
                break;
            default:
                System.out.println("Invalid choice.");
        }
    }

    private static void login() {
        System.out.print("Account number: ");
        String accountNumber = scanner.nextLine();

        System.out.print("PIN: ");
        String pin = scanner.nextLine();

        Account account = accounts.get(accountNumber);

        if (account == null || !account.pin.equals(pin)) {
            System.out.println("Invalid credentials.");
            return;
        }

        if (account.frozen) {
            System.out.println("Account is frozen.");
            return;
        }

        currentUser = accountNumber;
        String token = generateToken(accountNumber);
        sessions.put(token, accountNumber);

        System.out.println("Login successful.");
        System.out.println("Session: " + token);
    }

    private static String generateToken(String accountNumber) {
        return accountNumber + "-" + System.currentTimeMillis();
    }

    private static void logout() {
        currentUser = null;
        System.out.println("Logged out.");
    }

    private static void showAccountMenu() {
        System.out.println("\n=== ACCOUNT MENU ===");
        System.out.println("1. View balance");
        System.out.println("2. Deposit");
        System.out.println("3. Withdraw");
        System.out.println("4. Transfer");
        System.out.println("5. Transaction history");
        System.out.println("6. Update account details");
        System.out.println("7. Request statement");
        System.out.println("8. Admin operations");
        System.out.println("9. Logout");
        System.out.print("Choice: ");

        String choice = scanner.nextLine();

        switch (choice) {
            case "1":
                viewBalance();
                break;
            case "2":
                deposit();
                break;
            case "3":
                withdraw();
                break;
            case "4":
                transfer();
                break;
            case "5":
                showHistory();
                break;
            case "6":
                updateDetails();
                break;
            case "7":
                generateStatement();
                break;
            case "8":
                adminMenu();
                break;
            case "9":
                logout();
                break;
            default:
                System.out.println("Invalid choice.");
        }
    }

    private static Account getCurrentAccount() {
        return accounts.get(currentUser);
    }

    private static void viewBalance() {
        Account account = getCurrentAccount();
        System.out.println("Account: " + account.accountNumber);
        System.out.println("Owner: " + account.owner);
        System.out.println("Balance: " + account.balance);
    }

    private static void deposit() {
        System.out.print("Amount: ");
        BigDecimal amount = new BigDecimal(scanner.nextLine());

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            System.out.println("Invalid amount.");
            return;
        }

        Account account = getCurrentAccount();
        account.balance = account.balance.add(amount);

        recordTransaction(currentUser, currentUser, amount, "Deposit");
        account.history.add("Deposit: " + amount);

        System.out.println("Deposit completed.");
    }

    private static void withdraw() {
        System.out.print("Amount: ");
        BigDecimal amount = new BigDecimal(scanner.nextLine());

        Account account = getCurrentAccount();

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            System.out.println("Invalid amount.");
            return;
        }

        if (account.balance.compareTo(amount) < 0) {
            System.out.println("Insufficient funds.");
            return;
        }

        account.balance = account.balance.subtract(amount);
        recordTransaction(currentUser, currentUser, amount, "Withdrawal");
        account.history.add("Withdrawal: " + amount);

        System.out.println("Withdrawal completed.");
    }

    private static void transfer() {
        System.out.print("Destination account: ");
        String destination = scanner.nextLine();

        System.out.print("Amount: ");
        BigDecimal amount = new BigDecimal(scanner.nextLine());

        Account sourceAccount = getCurrentAccount();
        Account destinationAccount = accounts.get(destination);

        if (destinationAccount == null) {
            System.out.println("Destination not found.");
            return;
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            System.out.println("Invalid amount.");
            return;
        }

        if (sourceAccount.balance.compareTo(amount) < 0) {
            System.out.println("Insufficient funds.");
            return;
        }

        sourceAccount.balance =
                sourceAccount.balance.subtract(amount);
        destinationAccount.balance =
                destinationAccount.balance.add(amount);

        recordTransaction(currentUser, destination, amount, "Transfer");

        sourceAccount.history.add("Transfer to " + destination
                + ": " + amount);
        destinationAccount.history.add("Transfer from " + currentUser
                + ": " + amount);

        System.out.println("Transfer completed.");
    }

    private static void recordTransaction(String from, String to,
                                          BigDecimal amount,
                                          String description) {
        transactions.add(new Transaction(from, to, amount, description));
    }

    private static void showHistory() {
        Account account = getCurrentAccount();

        System.out.println("\n=== TRANSACTION HISTORY ===");

        for (String entry : account.history) {
            System.out.println(entry);
        }
    }

    private static void updateDetails() {
        Account account = getCurrentAccount();

        System.out.println("1. Update owner name");
        System.out.println("2. Update PIN");
        System.out.println("3. Update account number");
        System.out.print("Choice: ");

        String choice = scanner.nextLine();

        if (choice.equals("1")) {
            System.out.print("New owner name: ");
            account.owner = scanner.nextLine();
        } else if (choice.equals("2")) {
            System.out.print("New PIN: ");
            account.pin = scanner.nextLine();
        } else if (choice.equals("3")) {
            System.out.print("New account number: ");
            String newNumber = scanner.nextLine();
            accounts.remove(account.accountNumber);
            account.accountNumber = newNumber;
            accounts.put(newNumber, account);
            currentUser = newNumber;
        }

        System.out.println("Details updated.");
    }

    private static void generateStatement() {
        Account account = getCurrentAccount();
        String filename = account.accountNumber + "_statement.txt";

        try (PrintWriter writer = new PrintWriter(filename)) {
            writer.println("Account Statement");
            writer.println("Account: " + account.accountNumber);
            writer.println("Owner: " + account.owner);
            writer.println("Balance: " + account.balance);
            writer.println("Generated: " + LocalDateTime.now());

            for (String entry : account.history) {
                writer.println(entry);
            }

            System.out.println("Statement saved to " + filename);
        } catch (IOException e) {
            System.out.println("Unable to create statement.");
        }
    }

    private static void adminMenu() {
        Account account = getCurrentAccount();

        if (!account.role.equals("ADMIN")) {
            System.out.println("Access denied.");
            return;
        }

        System.out.println("\n=== ADMIN MENU ===");
        System.out.println("1. List accounts");
        System.out.println("2. Freeze account");
        System.out.println("3. Unfreeze account");
        System.out.println("4. Change account balance");
        System.out.println("5. View all transactions");
        System.out.println("6. Return");
        System.out.print("Choice: ");

        String choice = scanner.nextLine();

        switch (choice) {
            case "1":
                listAccounts();
                break;
            case "2":
                freezeAccount(true);
                break;
            case "3":
                freezeAccount(false);
                break;
            case "4":
                changeBalance();
                break;
            case "5":
                viewAllTransactions();
                break;
            case "6":
                return;
            default:
                System.out.println("Invalid choice.");
        }
    }

    private static void listAccounts() {
        for (Account account : accounts.values()) {
            System.out.println(account.accountNumber + " "
                    + account.owner + " " + account.balance);
        }
    }

    private static void freezeAccount(boolean freeze) {
        System.out.print("Account number: ");
        String number = scanner.nextLine();

        Account account = accounts.get(number);

        if (account == null) {
            System.out.println("Account not found.");
            return;
        }

        account.frozen = freeze;
        System.out.println(freeze ? "Account frozen." : "Account unfrozen.");
    }

    private static void changeBalance() {
        System.out.print("Account number: ");
        String number = scanner.nextLine();

        Account account = accounts.get(number);

        if (account == null) {
            System.out.println("Account not found.");
            return;
        }

        System.out.print("New balance: ");
        account.balance = new BigDecimal(scanner.nextLine());

        System.out.println("Balance changed.");
    }

    private static void viewAllTransactions() {
        for (Transaction transaction : transactions) {
            System.out.println(transaction.timestamp + " "
                    + transaction.from + " -> "
                    + transaction.to + " "
                    + transaction.amount + " "
                    + transaction.description);
        }
    }

    private static void exportTransactions() {
        try (PrintWriter writer =
                     new PrintWriter("transactions.csv")) {
            writer.println("ID,From,To,Amount,Timestamp,Description");

            for (Transaction transaction : transactions) {
                writer.println(transaction.id + ","
                        + transaction.from + ","
                        + transaction.to + ","
                        + transaction.amount + ","
                        + transaction.timestamp + ","
                        + transaction.description);
            }
        } catch (IOException e) {
            System.out.println("Export failed.");
        }
    }

    private static void searchTransactions() {
        System.out.print("Search account: ");
        String account = scanner.nextLine();

        for (Transaction transaction : transactions) {
            if (transaction.from.equals(account)
                    || transaction.to.equals(account)) {
                System.out.println(transaction.timestamp + " "
                        + transaction.description + " "
                        + transaction.amount);
            }
        }
    }

    private static void calculateInterest() {
        Account account = getCurrentAccount();
        BigDecimal rate = new BigDecimal("0.05");
        BigDecimal interest = account.balance.multiply(rate);

        account.balance = account.balance.add(interest);
        account.history.add("Interest: " + interest);

        System.out.println("Interest added: " + interest);
    }

    private static void transferToMultipleAccounts() {
        System.out.print("Number of destinations: ");
        int count = Integer.parseInt(scanner.nextLine());

        for (int i = 0; i < count; i++) {
            System.out.print("Destination: ");
            String destination = scanner.nextLine();

            System.out.print("Amount: ");
            BigDecimal amount = new BigDecimal(scanner.nextLine());

            Account target = accounts.get(destination);

            if (target != null) {
                target.balance = target.balance.add(amount);
            }
        }
    }

    private static void resetPin() {
        System.out.print("Account number: ");
        String number = scanner.nextLine();

        Account account = accounts.get(number);

        if (account == null) {
            System.out.println("Account not found.");
            return;
        }

        System.out.print("New PIN: ");
        account.pin = scanner.nextLine();

        System.out.println("PIN changed.");
    }

    private static void closeAccount() {
        System.out.print("Account number: ");
        String number = scanner.nextLine();

        accounts.remove(number);
        System.out.println("Account removed.");
    }

    private static void displaySystemInfo() {
        System.out.println("Accounts: " + accounts.size());
        System.out.println("Sessions: " + sessions.size());
        System.out.println("Transactions: " + transactions.size());
        System.out.println("System time: " + LocalDateTime.now());
    }

    private static void backupAccounts() {
        try (ObjectOutputStream output =
                     new ObjectOutputStream(
                             new FileOutputStream("accounts.backup"))) {
            output.writeObject(accounts);
            System.out.println("Backup completed.");
        } catch (IOException e) {
            System.out.println("Backup failed.");
        }
    }

    private static void importAccounts() {
        try (ObjectInputStream input =
                     new ObjectInputStream(
                             new FileInputStream("accounts.backup"))) {
            Object data = input.readObject();
            if (data instanceof Map) {
                accounts = (Map<String, Account>) data;
            }
            System.out.println("Import completed.");
        } catch (Exception e) {
            System.out.println("Import failed.");
        }
    }

    private static void auditLog(String event) {
        try (FileWriter writer =
                     new FileWriter("audit.log", true)) {
            writer.write(LocalDateTime.now() + " "
                    + currentUser + " " + event + "\n");
        } catch (IOException e) {
            System.out.println("Audit log unavailable.");
        }
    }

    private static void changeRole() {
        System.out.print("Account number: ");
        String number = scanner.nextLine();

        Account account = accounts.get(number);

        if (account == null) {
            return;
        }

        System.out.print("New role: ");
        account.role = scanner.nextLine();
    }

    private static void searchAccount() {
        System.out.print("Search owner: ");
        String query = scanner.nextLine().toLowerCase();

        for (Account account : accounts.values()) {
            if (account.owner.toLowerCase().contains(query)) {
                System.out.println(account.accountNumber + " "
                        + account.owner);
            }
        }
    }
}
