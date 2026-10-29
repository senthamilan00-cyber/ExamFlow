package examflow;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Prepared-statement access to the existing SQLite database. */
final class Database implements AutoCloseable {
    private final Connection connection;

    Database(Path directory) throws Exception {
        Files.createDirectories(directory);
        connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("examflow.sqlite"));
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS users(id INTEGER PRIMARY KEY,name TEXT NOT NULL,email TEXT UNIQUE NOT NULL,password TEXT NOT NULL,role TEXT NOT NULL CHECK(role IN ('student','admin')))");
            s.execute("CREATE TABLE IF NOT EXISTS exams(id INTEGER PRIMARY KEY,title TEXT NOT NULL,subject TEXT NOT NULL,description TEXT NOT NULL,duration INTEGER NOT NULL,published INTEGER NOT NULL DEFAULT 0,created TEXT DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE IF NOT EXISTS questions(id INTEGER PRIMARY KEY,exam_id INTEGER NOT NULL REFERENCES exams(id) ON DELETE CASCADE,prompt TEXT NOT NULL,options TEXT NOT NULL,answer INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS attempts(id INTEGER PRIMARY KEY,user_id INTEGER NOT NULL REFERENCES users(id),exam_id INTEGER NOT NULL REFERENCES exams(id),started INTEGER NOT NULL,deadline INTEGER NOT NULL,submitted INTEGER,answers TEXT NOT NULL DEFAULT '{}',score INTEGER,total INTEGER NOT NULL,UNIQUE(user_id,exam_id))");
            s.execute("CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY,user_id INTEGER NOT NULL REFERENCES users(id),expires INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS admin_account_events(id INTEGER PRIMARY KEY,actor_id INTEGER NOT NULL REFERENCES users(id),created_user_id INTEGER NOT NULL REFERENCES users(id),created_at INTEGER NOT NULL,conditions_version TEXT NOT NULL)");
        }
    }

    private PreparedStatement statement(String sql, Object... args) throws SQLException {
        PreparedStatement s = connection.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
        return s;
    }

    List<Map<String,Object>> all(String sql, Object... args) throws SQLException {
        try (PreparedStatement s = statement(sql,args); ResultSet r = s.executeQuery()) {
            List<Map<String,Object>> rows = new ArrayList<>();
            int count = r.getMetaData().getColumnCount();
            while (r.next()) {
                Map<String,Object> row = new LinkedHashMap<>();
                for (int i=1;i<=count;i++) row.put(r.getMetaData().getColumnLabel(i),r.getObject(i));
                rows.add(row);
            }
            return rows;
        }
    }
    Map<String,Object> one(String sql,Object... args) throws SQLException {
        List<Map<String,Object>> rows=all(sql,args);
        return rows.isEmpty()?null:rows.getFirst();
    }
    int update(String sql,Object... args) throws SQLException {
        try (PreparedStatement s=statement(sql,args)) { return s.executeUpdate(); }
    }
    long insert(String sql,Object... args) throws SQLException {
        update(sql,args);
        return ((Number)one("SELECT last_insert_rowid() id").get("id")).longValue();
    }
    void begin() throws SQLException { connection.setAutoCommit(false); }
    void commit() throws SQLException { connection.commit(); connection.setAutoCommit(true); }
    void rollback() throws SQLException { connection.rollback(); connection.setAutoCommit(true); }
    @Override public void close() throws SQLException { connection.close(); }
}
