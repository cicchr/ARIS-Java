package edu.rpi.aris.assign;

import edu.rpi.aris.assign.message.ErrorType;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Base64;

public class DBUtils {

    private static final Logger logger = LogManager.getLogger(DBUtils.class);
    private static final SecureRandom random = new SecureRandom();

    public static MessageDigest getDigest() {
        try {
            return MessageDigest.getInstance("SHA512", "BC");
        } catch (NoSuchAlgorithmException | NoSuchProviderException e) {
            logger.fatal("Failed to create MessageDigest", e);
            throw new RuntimeException("Unable to start server due to inability to verify user credentials", e);
        }
    }

    public static boolean checkPass(String pass, String salt, String savedHash) {
        MessageDigest digest = getDigest();
        digest.update(Base64.getDecoder().decode(salt));
        return Base64.getEncoder().encodeToString(digest.digest(pass.getBytes())).equals(savedHash);
    }

    public static Pair<String, ErrorType> setPassword(Connection connection, String username, String password) throws SQLException {
        if (username == null || username.length() == 0)
            return new ImmutablePair<>(null, ErrorType.INVALID_PASSWORD);
        if (password == null)
            password = RandomStringUtils.randomAlphabetic(16);
        try (PreparedStatement statement = connection.prepareStatement("UPDATE users SET salt = ?, password_hash = ? WHERE username = ?;");) {
            Pair<String, String> sh = getSaltAndHash(password);
            statement.setString(1, sh.getKey());
            statement.setString(2, sh.getValue());
            statement.setString(3, username);
            statement.executeUpdate();
            return new ImmutablePair<>(password, null);
        }
    }

    public static Pair<String, String> getSaltAndHash(String password) {
        MessageDigest digest = getDigest();
        byte[] saltBytes = new byte[16];
        random.nextBytes(saltBytes);
        String salt = Base64.getEncoder().encodeToString(saltBytes);
        digest.update(saltBytes);
        String hash = Base64.getEncoder().encodeToString(digest.digest(password.getBytes()));
        return new ImmutablePair<>(salt, hash);
    }
}