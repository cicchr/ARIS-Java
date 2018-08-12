package edu.rpi.aris.assign.message;

import edu.rpi.aris.assign.NetUtil;
import edu.rpi.aris.assign.User;
import edu.rpi.aris.assign.UserType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;

public class AssignmentCreateMsg extends Message {

    private static final Logger logger = LogManager.getLogger(AssignmentCreateMsg.class);

    private final int cid;
    private final ArrayList<Integer> problems = new ArrayList<>();
    private final String name;
    private final ZonedDateTime dueDate;

    public AssignmentCreateMsg(int cid, String name, ZonedDateTime dueDate) {
        this.cid = cid;
        this.name = name;
        this.dueDate = dueDate;
    }

    // DO NOT REMOVE!! Default constructor is required for gson deserialization
    private AssignmentCreateMsg() {
        cid = 0;
        name = null;
        dueDate = null;
    }

    public void addProof(int pid) {
        problems.add(pid);
    }

    public void addProofs(Collection<Integer> pids) {
        problems.addAll(pids);
    }

    @Override
    public ErrorType processMessage(Connection connection, User user) throws SQLException {
        if (!UserType.hasPermission(user, UserType.INSTRUCTOR))
            return ErrorType.UNAUTHORIZED;
        try (PreparedStatement select = connection.prepareStatement("SELECT id FROM assignment ORDER BY id DESC LIMIT 1;");
             PreparedStatement statement = connection.prepareStatement("INSERT INTO assignment VALUES(?, ?, ?, ?, ?, ?);")) {
            int id = 1;
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next())
                    id = rs.getInt(1) + 1;
            }
            for (int pid : problems) {
                statement.setInt(1, id);
                statement.setInt(2, cid);
                statement.setInt(3, pid);
                statement.setString(4, name);
                statement.setTimestamp(5, NetUtil.ZDTToTimestamp(dueDate));
                statement.setInt(6, user.uid);
                statement.addBatch();
            }
            statement.executeUpdate();
        }
        return null;
    }

    @Override
    public MessageType getMessageType() {
        return MessageType.CREATE_ASSIGNMENT;
    }

    @Override
    public boolean checkValid() {
        for (Integer i : problems)
            if (i == null)
                return false;
        return cid > 0 && name != null && dueDate != null;
    }
}
