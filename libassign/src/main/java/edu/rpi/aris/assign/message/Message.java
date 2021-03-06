package edu.rpi.aris.assign.message;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import edu.rpi.aris.assign.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.sql.Connection;
import java.time.ZonedDateTime;

public abstract class Message {

    private static final Gson gson;
    private static Logger logger = LogManager.getLogger(Message.class);

    static {
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(Message.class, new ArisMessageAdapter());
        gsonBuilder.registerTypeAdapter(byte[].class, new ByteArrayAdapter());
        gsonBuilder.registerTypeAdapter(ZonedDateTime.class, new ZDTGsonAdapter());
        gson = gsonBuilder.create();
    }

    private final transient Perm permission;
    private final transient boolean customPermCheck;

    protected Message(@NotNull Perm permission, boolean customPermCheck) {
        this.permission = permission;
        this.customPermCheck = customPermCheck;
    }

    protected Message(@NotNull Perm permission) {
        this(permission, false);
    }

    @NotNull
    private static Message parse(@NotNull MessageCommunication com) {
        try {
            Message msg = gson.fromJson(com.getReader(), Message.class);
            if (msg != null)
                logger.info("Received message: " + msg.getMessageType());
            else {
                logger.error("Received empty message");
                return new ErrorMsg(ErrorType.IO_ERROR, "Message not received");
            }
            if (msg instanceof DataMessage)
                ((DataMessage) msg).receiveData(com.getInputStream());
            if (!msg.checkValid()) {
                logger.error("Message not formatted properly");
                return new ErrorMsg(ErrorType.PARSE_ERR, "Improperly formatted json message");
            }
            return msg;
        } catch (JsonSyntaxException e) {
            logger.error("Failed to read message", e);
            return new ErrorMsg(ErrorType.PARSE_ERR, "Failed to read message");
        } catch (IOException e) {
            logger.error("Failed to read json from peer", e);
            return new ErrorMsg(ErrorType.IO_ERROR, "Failed to read json from peer");
        } catch (ArisException e) {
            logger.error("An error occurred while interacting with an Aris Module");
            return new ErrorMsg(ErrorType.MODULE_ERROR, e.getMessage());
        } catch (Exception e) {
            logger.error("An unknown exception occurred", e);
            return new ErrorMsg(ErrorType.EXCEPTION, e.getMessage());
        }
    }

    @Nullable
    public static Message get(@NotNull MessageCommunication com) {
        Message reply = parse(com);
        if (reply instanceof ErrorMsg) {
            com.handleErrorMsg((ErrorMsg) reply);
            return null;
        }
        return reply;
    }

    public final Perm getPermission() {
        return permission;
    }

    public final boolean hasCustomPermissionCheck() {
        return customPermCheck;
    }

    public final void send(@NotNull MessageCommunication com) throws Exception {
        logger.info("Sending message: " + getMessageType());
        gson.toJson(this, Message.class, com.getWriter());
        com.getWriter().flush();
        if (this instanceof DataMessage)
            ((DataMessage) this).sendData(com.getOutputStream());
    }

    /**
     * Sends the message represented by this {@link Message} object using the given
     * {@link MessageCommunication} and retrieves the reply from the {@link MessageCommunication}.
     * If an error occurs {@link MessageCommunication#handleErrorMsg(ErrorMsg)} is called to handle the error
     *
     * @param com The {@link MessageCommunication} object to communicate with. Cannot be null
     * @return The reply message or null if an error occurred. The return type (if it's not null) should be the same
     * type as the object this method was called on
     * @throws IOException If there is an error when communicating
     */
    @Nullable
    public final Message sendAndGet(@NotNull MessageCommunication com) throws Exception {
        send(com);
        Message reply = parse(com);
        if (!reply.getClass().equals(this.getClass())) {
            if (!(reply instanceof ErrorMsg))
                reply = new ErrorMsg(ErrorType.INCORRECT_MSG_TYPE, "Expected \"" + getMessageType() + "\" received \"" + reply.getMessageType().name() + "\"");
            com.handleErrorMsg((ErrorMsg) reply);
            return null;
        }
        return reply;
    }

    @Nullable
    public abstract ErrorType processMessage(@NotNull Connection connection, @NotNull User user, @NotNull ServerPermissions permissions) throws Exception;

    @NotNull
    public abstract MessageType getMessageType();

    public abstract boolean checkValid();

}
