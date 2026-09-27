package dev.autociv.simulation.economy;

/** Small ledger helper for transactional market payments. */
public final class Treasury {

    private double balance;

    public Treasury(double balance) {
        if (!Double.isFinite(balance) || balance < 0) {
            throw new IllegalArgumentException("Treasury balance must be finite and non-negative");
        }
        this.balance = balance;
    }

    public double balance() {
        return balance;
    }

    public boolean withdraw(double amount) {
        if (!Double.isFinite(amount) || amount < 0 || balance < amount) {
            return false;
        }
        balance -= amount;
        return true;
    }

    public void deposit(double amount) {
        if (!Double.isFinite(amount) || amount < 0) {
            throw new IllegalArgumentException("Deposit must be finite and non-negative");
        }
        double newBalance = balance + amount;
        if (!Double.isFinite(newBalance)) {
            throw new IllegalArgumentException("Treasury balance overflow");
        }
        balance = newBalance;
    }
}
