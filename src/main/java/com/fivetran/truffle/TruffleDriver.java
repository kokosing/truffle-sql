package com.fivetran.truffle;

import com.fivetran.truffle.parse.TruffleMeta;
import org.apache.calcite.avatica.AvaticaConnection;
import org.apache.calcite.avatica.DriverVersion;
import org.apache.calcite.avatica.Meta;
import org.apache.calcite.avatica.UnregisteredDriver;

import java.sql.DriverManager;
import java.sql.SQLException;

public class TruffleDriver extends UnregisteredDriver {
    static {
        try {
            DriverManager.registerDriver(new TruffleDriver());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected DriverVersion createDriverVersion() {
        return new DriverVersion("Truffle JDBC driver", "0.1", "Truffle", "0.1", true, 0, 1, 0, 1);
    }

    @Override
    protected String getConnectStringPrefix() {
        return "jdbc:truffle:";
    }

    @Override
    public Meta createMeta(AvaticaConnection connection) {
        return new TruffleMeta(connection);
    }

}
