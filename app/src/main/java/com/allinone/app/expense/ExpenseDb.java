package com.allinone.app.expense;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Money-manager database. v2 schema adds accounts, categories, people, loans,
 * loan payments and recurring rules, and upgrades the transactions table with
 * type / account columns while preserving existing rows.
 */
public class ExpenseDb extends SQLiteOpenHelper {

    private static final String DB_NAME = "expenses.db";
    private static final int DB_VERSION = 2;

    private static final String T_TX        = "expenses";       // transactions
    private static final String T_ACCOUNTS  = "accounts";
    private static final String T_CATEGORIES = "categories";
    private static final String T_PEOPLE    = "people";
    private static final String T_LOANS     = "loans";
    private static final String T_PAYMENTS  = "loan_payments";
    private static final String T_RECURRING = "recurring";

    public ExpenseDb(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    // ── Schema ───────────────────────────────────────────────────────────────
    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T_TX + "(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "type TEXT DEFAULT 'EXPENSE'," +
            "category TEXT," +
            "amount REAL," +
            "note TEXT," +
            "date_ms INTEGER," +
            "account_id INTEGER DEFAULT 0," +
            "to_account_id INTEGER DEFAULT 0)");
        createAuxTables(db);
        long cashId = seedDefaults(db);
        // Fresh install: nothing to migrate, but make the seed Cash account the default.
        db.execSQL("UPDATE " + T_TX + " SET account_id=" + cashId + " WHERE account_id=0");
    }

    private void createAuxTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + T_ACCOUNTS + "(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "name TEXT, type TEXT, opening_balance REAL DEFAULT 0, archived INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + T_CATEGORIES + "(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "name TEXT, color INTEGER, type TEXT, budget REAL DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + T_PEOPLE + "(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "name TEXT, phone TEXT, note TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + T_LOANS + "(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "person_id INTEGER, direction TEXT, principal REAL, date_ms INTEGER," +
            "due_ms INTEGER DEFAULT 0, note TEXT, status TEXT DEFAULT 'OPEN', account_id INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + T_PAYMENTS + "(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "loan_id INTEGER, amount REAL, date_ms INTEGER, account_id INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + T_RECURRING + "(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "type TEXT, category TEXT, amount REAL, note TEXT, account_id INTEGER DEFAULT 0," +
            "to_account_id INTEGER DEFAULT 0, interval_type TEXT, interval_n INTEGER DEFAULT 1," +
            "next_ms INTEGER, enabled INTEGER DEFAULT 1)");
    }

    /** Inserts default categories + a Cash account. Returns the Cash account id. */
    private long seedDefaults(SQLiteDatabase db) {
        for (int i = 0; i < Category.DEFAULT_EXPENSE_NAMES.length; i++) {
            ContentValues cv = new ContentValues();
            cv.put("name", Category.DEFAULT_EXPENSE_NAMES[i]);
            cv.put("color", Category.DEFAULT_EXPENSE_COLORS[i]);
            cv.put("type", Category.TYPE_EXPENSE);
            cv.put("budget", 0);
            db.insert(T_CATEGORIES, null, cv);
        }
        for (int i = 0; i < Category.DEFAULT_INCOME_NAMES.length; i++) {
            ContentValues cv = new ContentValues();
            cv.put("name", Category.DEFAULT_INCOME_NAMES[i]);
            cv.put("color", Category.DEFAULT_INCOME_COLORS[i]);
            cv.put("type", Category.TYPE_INCOME);
            cv.put("budget", 0);
            db.insert(T_CATEGORIES, null, cv);
        }
        ContentValues cash = new ContentValues();
        cash.put("name", "Cash");
        cash.put("type", "Cash");
        cash.put("opening_balance", 0);
        cash.put("archived", 0);
        return db.insert(T_ACCOUNTS, null, cash);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) {
            // Preserve existing expenses: add new columns, create aux tables, seed, then
            // attach all legacy rows to the new Cash account.
            db.execSQL("ALTER TABLE " + T_TX + " ADD COLUMN type TEXT DEFAULT 'EXPENSE'");
            db.execSQL("ALTER TABLE " + T_TX + " ADD COLUMN account_id INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE " + T_TX + " ADD COLUMN to_account_id INTEGER DEFAULT 0");
            createAuxTables(db);
            long cashId = seedDefaults(db);
            db.execSQL("UPDATE " + T_TX + " SET type='EXPENSE' WHERE type IS NULL");
            db.execSQL("UPDATE " + T_TX + " SET account_id=" + cashId + " WHERE account_id=0 OR account_id IS NULL");
        }
    }

    // ── Transactions ───────────────────────────────────────────────────────────
    public long insert(Expense e) {
        return getWritableDatabase().insert(T_TX, null, txValues(e));
    }

    public void update(Expense e) {
        getWritableDatabase().update(T_TX, txValues(e), "id=?", new String[]{String.valueOf(e.id)});
    }

    private ContentValues txValues(Expense e) {
        ContentValues cv = new ContentValues();
        cv.put("type", e.type == null ? Expense.TYPE_EXPENSE : e.type);
        cv.put("category", e.category);
        cv.put("amount", e.amount);
        cv.put("note", e.note);
        cv.put("date_ms", e.dateMillis);
        cv.put("account_id", e.accountId);
        cv.put("to_account_id", e.toAccountId);
        return cv;
    }

    public void delete(long id) {
        getWritableDatabase().delete(T_TX, "id=?", new String[]{String.valueOf(id)});
    }

    public List<Expense> query(long fromMs, long toMs) {
        return queryFiltered(fromMs, toMs, null, null, 0, null);
    }

    /**
     * Flexible transaction query. Any of typeFilter / categoryFilter / accountFilter (0)
     * / search may be null/0 to skip that filter.
     */
    public List<Expense> queryFiltered(long fromMs, long toMs, String typeFilter,
                                       String categoryFilter, long accountFilter, String search) {
        StringBuilder where = new StringBuilder("date_ms >= ? AND date_ms <= ?");
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(fromMs));
        args.add(String.valueOf(toMs));
        if (typeFilter != null) { where.append(" AND type = ?"); args.add(typeFilter); }
        if (categoryFilter != null) { where.append(" AND category = ?"); args.add(categoryFilter); }
        if (accountFilter > 0) {
            where.append(" AND (account_id = ? OR to_account_id = ?)");
            args.add(String.valueOf(accountFilter));
            args.add(String.valueOf(accountFilter));
        }
        if (search != null && !search.trim().isEmpty()) {
            where.append(" AND (note LIKE ? OR category LIKE ?)");
            String like = "%" + search.trim() + "%";
            args.add(like); args.add(like);
        }
        List<Expense> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(T_TX, null, where.toString(),
            args.toArray(new String[0]), null, null, "date_ms DESC");
        while (c.moveToNext()) list.add(txFromCursor(c));
        c.close();
        return list;
    }

    /** Sum of a given transaction type in a date range. */
    public double sumByType(long fromMs, long toMs, String type) {
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT SUM(amount) FROM " + T_TX + " WHERE type=? AND date_ms>=? AND date_ms<=?",
            new String[]{type, String.valueOf(fromMs), String.valueOf(toMs)});
        double sum = 0;
        if (c.moveToFirst() && !c.isNull(0)) sum = c.getDouble(0);
        c.close();
        return sum;
    }

    /** Legacy helper retained: total expense amount in range. */
    public double sumAmount(long fromMs, long toMs) {
        return sumByType(fromMs, toMs, Expense.TYPE_EXPENSE);
    }

    /** Expense totals grouped by category in a range (for the pie chart). */
    public Map<String, Double> categoryTotals(long fromMs, long toMs, String type) {
        Map<String, Double> map = new LinkedHashMap<>();
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT category, SUM(amount) FROM " + T_TX +
            " WHERE type=? AND date_ms>=? AND date_ms<=? GROUP BY category ORDER BY SUM(amount) DESC",
            new String[]{type, String.valueOf(fromMs), String.valueOf(toMs)});
        while (c.moveToNext()) {
            String cat = c.isNull(0) ? "Other" : c.getString(0);
            map.put(cat, c.getDouble(1));
        }
        c.close();
        return map;
    }

    /** Amount spent in an expense category within a range (for per-category budgets). */
    public double spentInCategory(String category, long fromMs, long toMs) {
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT SUM(amount) FROM " + T_TX +
            " WHERE type='EXPENSE' AND category=? AND date_ms>=? AND date_ms<=?",
            new String[]{category, String.valueOf(fromMs), String.valueOf(toMs)});
        double sum = 0;
        if (c.moveToFirst() && !c.isNull(0)) sum = c.getDouble(0);
        c.close();
        return sum;
    }

    public List<Expense> queryAll() {
        List<Expense> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(T_TX, null, null, null, null, null, "date_ms ASC");
        while (c.moveToNext()) list.add(txFromCursor(c));
        c.close();
        return list;
    }

    public void deleteAllTransactions() {
        getWritableDatabase().delete(T_TX, null, null);
    }

    private Expense txFromCursor(Cursor c) {
        Expense e = new Expense();
        e.id = c.getLong(c.getColumnIndexOrThrow("id"));
        e.type = c.getString(c.getColumnIndexOrThrow("type"));
        e.category = c.getString(c.getColumnIndexOrThrow("category"));
        e.amount = c.getDouble(c.getColumnIndexOrThrow("amount"));
        e.note = c.getString(c.getColumnIndexOrThrow("note"));
        e.dateMillis = c.getLong(c.getColumnIndexOrThrow("date_ms"));
        e.accountId = c.getLong(c.getColumnIndexOrThrow("account_id"));
        e.toAccountId = c.getLong(c.getColumnIndexOrThrow("to_account_id"));
        if (e.type == null) e.type = Expense.TYPE_EXPENSE;
        return e;
    }

    // ── Accounts ────────────────────────────────────────────────────────────────
    public long insertAccount(Account a) {
        ContentValues cv = new ContentValues();
        cv.put("name", a.name);
        cv.put("type", a.type);
        cv.put("opening_balance", a.openingBalance);
        cv.put("archived", a.archived ? 1 : 0);
        return getWritableDatabase().insert(T_ACCOUNTS, null, cv);
    }

    public void updateAccount(Account a) {
        ContentValues cv = new ContentValues();
        cv.put("name", a.name);
        cv.put("type", a.type);
        cv.put("opening_balance", a.openingBalance);
        cv.put("archived", a.archived ? 1 : 0);
        getWritableDatabase().update(T_ACCOUNTS, cv, "id=?", new String[]{String.valueOf(a.id)});
    }

    public void deleteAccount(long id) {
        getWritableDatabase().delete(T_ACCOUNTS, "id=?", new String[]{String.valueOf(id)});
    }

    public List<Account> queryAccounts(boolean includeArchived) {
        List<Account> list = new ArrayList<>();
        String sel = includeArchived ? null : "archived=0";
        Cursor c = getReadableDatabase().query(T_ACCOUNTS, null, sel, null, null, null, "id ASC");
        while (c.moveToNext()) {
            Account a = new Account(
                c.getLong(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("name")),
                c.getString(c.getColumnIndexOrThrow("type")),
                c.getDouble(c.getColumnIndexOrThrow("opening_balance")),
                c.getInt(c.getColumnIndexOrThrow("archived")) == 1);
            a.balance = accountBalance(a.id, a.openingBalance);
            list.add(a);
        }
        c.close();
        return list;
    }

    public Account getAccount(long id) {
        Cursor c = getReadableDatabase().query(T_ACCOUNTS, null, "id=?",
            new String[]{String.valueOf(id)}, null, null, null);
        Account a = null;
        if (c.moveToFirst()) {
            a = new Account(
                c.getLong(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("name")),
                c.getString(c.getColumnIndexOrThrow("type")),
                c.getDouble(c.getColumnIndexOrThrow("opening_balance")),
                c.getInt(c.getColumnIndexOrThrow("archived")) == 1);
            a.balance = accountBalance(a.id, a.openingBalance);
        }
        c.close();
        return a;
    }

    /** opening + income - expense + transfers-in - transfers-out for this account. */
    public double accountBalance(long accountId, double opening) {
        double bal = opening;
        bal += scalar("SELECT SUM(amount) FROM " + T_TX + " WHERE type='INCOME' AND account_id=?", accountId);
        bal -= scalar("SELECT SUM(amount) FROM " + T_TX + " WHERE type='EXPENSE' AND account_id=?", accountId);
        bal += scalar("SELECT SUM(amount) FROM " + T_TX + " WHERE type='TRANSFER' AND to_account_id=?", accountId);
        bal -= scalar("SELECT SUM(amount) FROM " + T_TX + " WHERE type='TRANSFER' AND account_id=?", accountId);
        return bal;
    }

    public double totalAccountsBalance() {
        double total = 0;
        for (Account a : queryAccounts(false)) total += a.balance;
        return total;
    }

    private double scalar(String sql, long arg) {
        Cursor c = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(arg)});
        double v = 0;
        if (c.moveToFirst() && !c.isNull(0)) v = c.getDouble(0);
        c.close();
        return v;
    }

    // ── Categories ──────────────────────────────────────────────────────────────
    public long insertCategory(Category cat) {
        getWritableDatabase().insert(T_CATEGORIES, null, catValues(cat));
        return -1;
    }

    public void updateCategory(Category cat) {
        getWritableDatabase().update(T_CATEGORIES, catValues(cat), "id=?",
            new String[]{String.valueOf(cat.id)});
    }

    private ContentValues catValues(Category cat) {
        ContentValues cv = new ContentValues();
        cv.put("name", cat.name);
        cv.put("color", cat.color);
        cv.put("type", cat.type);
        cv.put("budget", cat.budget);
        return cv;
    }

    public void deleteCategory(long id) {
        getWritableDatabase().delete(T_CATEGORIES, "id=?", new String[]{String.valueOf(id)});
    }

    /** typeFilter null = all categories. */
    public List<Category> queryCategories(String typeFilter) {
        List<Category> list = new ArrayList<>();
        String sel = typeFilter == null ? null : "type=?";
        String[] args = typeFilter == null ? null : new String[]{typeFilter};
        Cursor c = getReadableDatabase().query(T_CATEGORIES, null, sel, args, null, null, "name ASC");
        while (c.moveToNext()) {
            list.add(new Category(
                c.getLong(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("name")),
                c.getInt(c.getColumnIndexOrThrow("color")),
                c.getString(c.getColumnIndexOrThrow("type")),
                c.getDouble(c.getColumnIndexOrThrow("budget"))));
        }
        c.close();
        return list;
    }

    public Category getCategoryByName(String name) {
        if (name == null) return null;
        Cursor c = getReadableDatabase().query(T_CATEGORIES, null, "name=?",
            new String[]{name}, null, null, null, "1");
        Category cat = null;
        if (c.moveToFirst()) {
            cat = new Category(
                c.getLong(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("name")),
                c.getInt(c.getColumnIndexOrThrow("color")),
                c.getString(c.getColumnIndexOrThrow("type")),
                c.getDouble(c.getColumnIndexOrThrow("budget")));
        }
        c.close();
        return cat;
    }

    // ── People ────────────────────────────────────────────────────────────────
    public long insertPerson(Person p) {
        ContentValues cv = new ContentValues();
        cv.put("name", p.name);
        cv.put("phone", p.phone);
        cv.put("note", p.note);
        return getWritableDatabase().insert(T_PEOPLE, null, cv);
    }

    public void updatePerson(Person p) {
        ContentValues cv = new ContentValues();
        cv.put("name", p.name);
        cv.put("phone", p.phone);
        cv.put("note", p.note);
        getWritableDatabase().update(T_PEOPLE, cv, "id=?", new String[]{String.valueOf(p.id)});
    }

    public void deletePerson(long id) {
        SQLiteDatabase db = getWritableDatabase();
        // Remove the person's loans + their payments too.
        Cursor c = db.query(T_LOANS, new String[]{"id"}, "person_id=?",
            new String[]{String.valueOf(id)}, null, null, null);
        while (c.moveToNext()) {
            long loanId = c.getLong(0);
            db.delete(T_PAYMENTS, "loan_id=?", new String[]{String.valueOf(loanId)});
        }
        c.close();
        db.delete(T_LOANS, "person_id=?", new String[]{String.valueOf(id)});
        db.delete(T_PEOPLE, "id=?", new String[]{String.valueOf(id)});
    }

    public List<Person> queryPeople() {
        List<Person> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(T_PEOPLE, null, null, null, null, null, "name ASC");
        while (c.moveToNext()) {
            Person p = new Person(
                c.getLong(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("name")),
                c.getString(c.getColumnIndexOrThrow("phone")),
                c.getString(c.getColumnIndexOrThrow("note")));
            p.netReceivable = personNet(p.id);
            list.add(p);
        }
        c.close();
        return list;
    }

    public Person getPerson(long id) {
        Cursor c = getReadableDatabase().query(T_PEOPLE, null, "id=?",
            new String[]{String.valueOf(id)}, null, null, null);
        Person p = null;
        if (c.moveToFirst()) {
            p = new Person(
                c.getLong(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("name")),
                c.getString(c.getColumnIndexOrThrow("phone")),
                c.getString(c.getColumnIndexOrThrow("note")));
        }
        c.close();
        return p;
    }

    /** + means the person owes you (net), - means you owe them. */
    public double personNet(long personId) {
        double net = 0;
        for (Loan l : queryLoans(personId, null)) {
            if (Loan.LENT.equals(l.direction)) net += l.outstanding;
            else net -= l.outstanding;
        }
        return net;
    }

    // ── Loans ──────────────────────────────────────────────────────────────────
    public long insertLoan(Loan l) {
        return getWritableDatabase().insert(T_LOANS, null, loanValues(l));
    }

    public void updateLoan(Loan l) {
        getWritableDatabase().update(T_LOANS, loanValues(l), "id=?",
            new String[]{String.valueOf(l.id)});
    }

    private ContentValues loanValues(Loan l) {
        ContentValues cv = new ContentValues();
        cv.put("person_id", l.personId);
        cv.put("direction", l.direction);
        cv.put("principal", l.principal);
        cv.put("date_ms", l.dateMillis);
        cv.put("due_ms", l.dueMillis);
        cv.put("note", l.note);
        cv.put("status", l.status);
        cv.put("account_id", l.accountId);
        return cv;
    }

    public void deleteLoan(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(T_PAYMENTS, "loan_id=?", new String[]{String.valueOf(id)});
        db.delete(T_LOANS, "id=?", new String[]{String.valueOf(id)});
    }

    /** personId 0 = all people; statusFilter null = all. */
    public List<Loan> queryLoans(long personId, String statusFilter) {
        StringBuilder where = new StringBuilder("1=1");
        List<String> args = new ArrayList<>();
        if (personId > 0) { where.append(" AND person_id=?"); args.add(String.valueOf(personId)); }
        if (statusFilter != null) { where.append(" AND status=?"); args.add(statusFilter); }
        List<Loan> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(T_LOANS, null, where.toString(),
            args.toArray(new String[0]), null, null, "date_ms DESC");
        while (c.moveToNext()) {
            Loan l = loanFromCursor(c);
            l.paid = loanPaid(l.id);
            l.outstanding = Math.max(0, l.principal - l.paid);
            Person p = getPerson(l.personId);
            l.personName = p != null ? p.name : "(unknown)";
            list.add(l);
        }
        c.close();
        return list;
    }

    public Loan getLoan(long id) {
        Cursor c = getReadableDatabase().query(T_LOANS, null, "id=?",
            new String[]{String.valueOf(id)}, null, null, null);
        Loan l = null;
        if (c.moveToFirst()) {
            l = loanFromCursor(c);
            l.paid = loanPaid(l.id);
            l.outstanding = Math.max(0, l.principal - l.paid);
            Person p = getPerson(l.personId);
            l.personName = p != null ? p.name : "(unknown)";
        }
        c.close();
        return l;
    }

    private Loan loanFromCursor(Cursor c) {
        return new Loan(
            c.getLong(c.getColumnIndexOrThrow("id")),
            c.getLong(c.getColumnIndexOrThrow("person_id")),
            c.getString(c.getColumnIndexOrThrow("direction")),
            c.getDouble(c.getColumnIndexOrThrow("principal")),
            c.getLong(c.getColumnIndexOrThrow("date_ms")),
            c.getLong(c.getColumnIndexOrThrow("due_ms")),
            c.getString(c.getColumnIndexOrThrow("note")),
            c.getString(c.getColumnIndexOrThrow("status")),
            c.getLong(c.getColumnIndexOrThrow("account_id")));
    }

    public double loanPaid(long loanId) {
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT SUM(amount) FROM " + T_PAYMENTS + " WHERE loan_id=?",
            new String[]{String.valueOf(loanId)});
        double v = 0;
        if (c.moveToFirst() && !c.isNull(0)) v = c.getDouble(0);
        c.close();
        return v;
    }

    /** Re-evaluates a loan's status based on payments (auto-settle at fully paid). */
    public void refreshLoanStatus(long loanId) {
        Loan l = getLoan(loanId);
        if (l == null) return;
        String status = (l.principal - l.paid) <= 0.001 ? Loan.STATUS_SETTLED : Loan.STATUS_OPEN;
        ContentValues cv = new ContentValues();
        cv.put("status", status);
        getWritableDatabase().update(T_LOANS, cv, "id=?", new String[]{String.valueOf(loanId)});
    }

    public long insertPayment(LoanPayment p) {
        ContentValues cv = new ContentValues();
        cv.put("loan_id", p.loanId);
        cv.put("amount", p.amount);
        cv.put("date_ms", p.dateMillis);
        cv.put("account_id", p.accountId);
        long id = getWritableDatabase().insert(T_PAYMENTS, null, cv);
        refreshLoanStatus(p.loanId);
        return id;
    }

    public void deletePayment(long id, long loanId) {
        getWritableDatabase().delete(T_PAYMENTS, "id=?", new String[]{String.valueOf(id)});
        refreshLoanStatus(loanId);
    }

    public List<LoanPayment> queryPayments(long loanId) {
        List<LoanPayment> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(T_PAYMENTS, null, "loan_id=?",
            new String[]{String.valueOf(loanId)}, null, null, "date_ms DESC");
        while (c.moveToNext()) {
            list.add(new LoanPayment(
                c.getLong(c.getColumnIndexOrThrow("id")),
                c.getLong(c.getColumnIndexOrThrow("loan_id")),
                c.getDouble(c.getColumnIndexOrThrow("amount")),
                c.getLong(c.getColumnIndexOrThrow("date_ms")),
                c.getLong(c.getColumnIndexOrThrow("account_id"))));
        }
        c.close();
        return list;
    }

    /** Total others owe you across all open LENT loans. */
    public double totalReceivable() {
        double t = 0;
        for (Loan l : queryLoans(0, Loan.STATUS_OPEN)) {
            if (Loan.LENT.equals(l.direction)) t += l.outstanding;
        }
        return t;
    }

    /** Total you owe across all open BORROWED loans. */
    public double totalPayable() {
        double t = 0;
        for (Loan l : queryLoans(0, Loan.STATUS_OPEN)) {
            if (Loan.BORROWED.equals(l.direction)) t += l.outstanding;
        }
        return t;
    }

    // ── Recurring rules ──────────────────────────────────────────────────────────
    public long insertRecurring(RecurringRule r) {
        return getWritableDatabase().insert(T_RECURRING, null, recValues(r));
    }

    public void updateRecurring(RecurringRule r) {
        getWritableDatabase().update(T_RECURRING, recValues(r), "id=?",
            new String[]{String.valueOf(r.id)});
    }

    private ContentValues recValues(RecurringRule r) {
        ContentValues cv = new ContentValues();
        cv.put("type", r.type);
        cv.put("category", r.category);
        cv.put("amount", r.amount);
        cv.put("note", r.note);
        cv.put("account_id", r.accountId);
        cv.put("to_account_id", r.toAccountId);
        cv.put("interval_type", r.intervalType);
        cv.put("interval_n", r.intervalN);
        cv.put("next_ms", r.nextMillis);
        cv.put("enabled", r.enabled ? 1 : 0);
        return cv;
    }

    public void deleteRecurring(long id) {
        getWritableDatabase().delete(T_RECURRING, "id=?", new String[]{String.valueOf(id)});
    }

    public List<RecurringRule> queryRecurring() {
        List<RecurringRule> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(T_RECURRING, null, null, null, null, null, "next_ms ASC");
        while (c.moveToNext()) list.add(recFromCursor(c));
        c.close();
        return list;
    }

    /** Enabled rules whose next post time is at/before now. */
    public List<RecurringRule> queryDueRecurring(long now) {
        List<RecurringRule> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(T_RECURRING, null, "enabled=1 AND next_ms<=?",
            new String[]{String.valueOf(now)}, null, null, "next_ms ASC");
        while (c.moveToNext()) list.add(recFromCursor(c));
        c.close();
        return list;
    }

    private RecurringRule recFromCursor(Cursor c) {
        return new RecurringRule(
            c.getLong(c.getColumnIndexOrThrow("id")),
            c.getString(c.getColumnIndexOrThrow("type")),
            c.getString(c.getColumnIndexOrThrow("category")),
            c.getDouble(c.getColumnIndexOrThrow("amount")),
            c.getString(c.getColumnIndexOrThrow("note")),
            c.getLong(c.getColumnIndexOrThrow("account_id")),
            c.getLong(c.getColumnIndexOrThrow("to_account_id")),
            c.getString(c.getColumnIndexOrThrow("interval_type")),
            c.getInt(c.getColumnIndexOrThrow("interval_n")),
            c.getLong(c.getColumnIndexOrThrow("next_ms")),
            c.getInt(c.getColumnIndexOrThrow("enabled")) == 1);
    }
}
