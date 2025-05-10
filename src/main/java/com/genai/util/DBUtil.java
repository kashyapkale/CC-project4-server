package com.genai.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DBUtil {
    // Reads MySQL config from Lambda environment variables
    private static final String URL = "jdbc:mysql://" + System.getenv("DB_HOST") + ":3306/p4_kashyapkale";
    private static final String USER = System.getenv("DB_USER");
    private static final String PASS = System.getenv("DB_PASS");

    private DBUtil() {
        // prevent instantiation
    }

    public static Connection get() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASS);
    }
}
