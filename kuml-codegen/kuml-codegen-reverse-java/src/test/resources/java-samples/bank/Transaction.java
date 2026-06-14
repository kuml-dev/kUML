package bank;

public class Transaction {
    private String transactionId;
    private double amount;
    private Account account;

    public Transaction(String transactionId, double amount, Account account) {
        this.transactionId = transactionId;
        this.amount = amount;
        this.account = account;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public double getAmount() {
        return amount;
    }

    public Account getAccount() {
        return account;
    }
}
