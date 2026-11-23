package com.example;

import com.mysql.cj.jdbc.MysqlDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.sql.Connection;
import java.sql.SQLException;


@Configuration
public class DatabaseConfig {

    private String user = System.getenv("admin");
    private String password = System.getenv("adminpass");
    private String serverName = System.getenv("192.168.56.101"); // db host name, like localhost without the port
    private String dbName = System.getenv("team-member-project");
    private MysqlDataSource datasource = new MysqlDataSource();

    public DatabaseConfig() {
        datasource.setPassword(password);
        datasource.setUser(user);
        datasource.setServerName(serverName);
        datasource.setDatabaseName(dbName);
        datasource.setPort(3306); // default config
        datasource.setURL("jdbc:mysql://" + serverName + ":3306/" + dbName);


    }

    @Bean
    public Connection getConnection() throws SQLException {
        return datasource.getConnection();
    }
}
