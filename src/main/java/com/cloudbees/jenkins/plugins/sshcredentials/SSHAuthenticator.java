/*
 * The MIT License
 *
 * Copyright (c) 2011-2012, CloudBees, Inc., Stephen Connolly.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.cloudbees.jenkins.plugins.sshcredentials;

import com.cloudbees.plugins.credentials.Credentials;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.BuildListener;
import hudson.model.Hudson;
import hudson.model.TaskListener;
import hudson.util.StreamTaskListener;
import net.jcip.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Abstraction for something that can authenticate an SSH connection.
 *
 * @param <C> the type of connection.
 * @param <U> the user to authenticate.
 */
public abstract class SSHAuthenticator<C, U extends SSHUser> {
    /**
     * Our connection.
     */
    @NonNull
    private final C connection;

    /**
     * Our user details.
     */
    @NonNull
    private final U user;

    /**
     * Lock to prevent threading issues.
     */
    @NonNull
    private final Object lock = new Object();

    /**
     * Authentication state.
     */
    @CheckForNull
    @GuardedBy("lock")
    private Boolean authenticated = null;

    /**
     * Subtypes are expected to report authentication failures to this listener.
     *
     * For backward compatibility with clients that do not supply a valid listener, use one that's connected
     * to server's stderr. This way, at least we know the error will be reported somewhere.
     */
    @NonNull
    private volatile TaskListener listener = StreamTaskListener.fromStderr();

    /**
     * Constructor.
     *
     * @param connection the connection we will be authenticating.
     */
    protected SSHAuthenticator(@NonNull C connection, @NonNull U user) {
        connection.getClass(); // throw NPE if null
        user.getClass(); // throw NPE if null
        this.connection = connection;
        this.user = user;
    }

    @NonNull
    public TaskListener getListener() {
        return listener;
    }

    /**
     * Sets the {@link TaskListener} that receives errors that happen during the authentication.
     *
     * If you are doing this as a part of a build, pass in your {@link BuildListener}.
     * Pass in null to suppress the error reporting. Doing so is useful if the caller intends
     * to try another {@link SSHAuthenticator} when this one fails.
     *
     * For assisting troubleshooting with callers that do not provide a valid listener,
     * by default the errors will be sent to stderr of the server.
     */
    public void setListener(TaskListener listener) {
        if (listener==null) listener=TaskListener.NULL;
        this.listener = listener;
    }

    /**
     * Creates an authenticator that may be able to authenticate the supplied connection with the supplied user.
     *
     * @param connection the connection to authenticate on.
     * @param user       the user to authenticate with.
     * @param <C>        the type of connection.
     * @param <U>        the type of user.
     * @return a {@link SSHAuthenticator} that may or may not be able to successfully authenticate.
     */
    @NonNull
    public static <C, U extends SSHUser> SSHAuthenticator<C, U> newInstance(@NonNull C connection, @NonNull U user) {
        connection.getClass(); // throw NPE if null
        user.getClass(); // throw NPE if null
        for (SSHAuthenticatorFactory factory : Hudson.getInstance().getExtensionList(SSHAuthenticatorFactory.class)) {
            SSHAuthenticator<C, U> result = factory.newInstance(connection, user);
            if (result != null && result.canAuthenticate()) {
                return result;
            }
        }
        return new SSHAuthenticator<C, U>(connection, user) {
            @Override
            protected boolean doAuthenticate() {
                return false;
            }
        };
    }

    /**
     * Returns {@code true} if and only if the supplied connection class and user class are supported by at least one
     * factory.
     *
     * @param connectionClass the connection class.
     * @param userClass       the user class.
     * @param <C>             the type of connection.
     * @param <U>             the type of user.
     * @return {@code true} if and only if the supplied connection class and user class are supported by at least one
     *         factory.
     */
    public static <C, U extends SSHUser> boolean isSupported(@NonNull Class<C> connectionClass,
                                                             @NonNull Class<U> userClass) {
        connectionClass.getClass(); // throw NPE if null
        userClass.getClass(); // throw NPE if null
        for (SSHAuthenticatorFactory factory : Hudson.getInstance().getExtensionList(SSHAuthenticatorFactory.class)) {
            if (factory.supports(connectionClass, userClass)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Filters {@link Credentials} returning only those which are supported with the specified type of connection.
     *
     * @param credentials     the credentials to filter.
     * @param connectionClass the type of connection to filter for.
     * @return a list of {@link SSHUser} credentials appropriate for use with the supplied type of connection.
     */
    public static List<? extends SSHUser> filter(Iterable<? extends Credentials> credentials,
                                                 Class<?> connectionClass) {
        List<SSHUser> result = new ArrayList<SSHUser>();
        for (Credentials credential : credentials) {
            if (credential instanceof SSHUser) {
                final SSHUser user = (SSHUser) credential;
                if (isSupported(connectionClass, user.getClass())) {
                    result.add(user);
                }
            }
        }
        return result;
    }

    /**
     * Returns {@code true} if the bound connection is in a state where authentication can be tried using the
     * supplied credentials.
     * <p/>
     * Subclasses can override this if they can tell whether it is possible to make an authentication attempt, default
     * implementation is one-shot always.
     *
     * @return {@code true} if the bound connection is in a state where authentication can be tried using the
     *         supplied credentials.
     */
    public boolean canAuthenticate() {
        synchronized (lock) {
            return authenticated == null;
        }
    }

    /**
     * Returns {@code true} if the bound connection has been authenticated.
     *
     * @return {@code true} if the bound connection has been authenticated.
     */
    public final boolean isAuthenticated() {
        synchronized (lock) {
            return authenticated != null && authenticated;
        }
    }

    /**
     * Gets the bound connection.
     *
     * @return the bound connection.
     */
    @NonNull
    protected final C getConnection() {
        return connection;
    }

    /**
     * Gets the supplied credentials.
     *
     * @return the supplied credentials.
     */
    @NonNull
    public U getUser() {
        return user;
    }

    /**
     * SPI for authenticating the bound connection using the supplied credentials.
     *
     * As a guideline, authentication errors should be reported to {@link #getListener()}
     * before this method returns with {@code false}. This helps an user better understand
     * what is tried and failing. Logging can be used in addition to this to capture further details.
     * (in contrast, please avoid reporting a success to the listener to improve S/N ratio)
     *
     * @return {@code true} if and only if authentication was successful.
     */
    protected abstract boolean doAuthenticate();

    /**
     * Returns the mode of authentication that this {@link SSHAuthenticator} supports.
     *
     * @return the mode of authentication that this {@link SSHAuthenticator} supports.
     * @since 0.2
     */
    @NonNull
    public Mode getAuthenticationMode() {
        return Mode.AFTER_CONNECT;
    }

    /**
     * @deprecated as of 0.3
     *      Use {@link #authenticate(TaskListener)} and provide a listener to receive errors.
     */
    public final boolean authenticate() {
        synchronized (lock) {
            if (canAuthenticate()) {
                try {
                    authenticated = doAuthenticate();
                } catch (Throwable t) {
                    Logger.getLogger(getClass().getName())
                            .log(Level.WARNING, "Uncaught exception escaped doAuthenticate method", t);
                    authenticated = false;
                }
            }
            return isAuthenticated() || Mode.BEFORE_CONNECT.equals(getAuthenticationMode());
        }
    }

    /**
     * Authenticate the bound connection using the supplied credentials.
     *
     * @return For an {@link #getAuthenticationMode()} of {@link Mode#BEFORE_CONNECT} the return value is
     *         always {@code true} otherwise the return value is {@code true} if and only if authentication was
     *         successful.
     */
    public final boolean authenticate(TaskListener listener) {
        setListener(listener);
        return authenticate();
    }

    /**
     * Reflects the different styles of applying authentication.
     * @since 0.2
     */
    public static enum Mode {
        /**
         * This {@link SSHAuthenticator} performs authentication before establishing the connection.
         */
        BEFORE_CONNECT,
        /**
         * This {@link SSHAuthenticator} performs authentication after establishing the connection.
         */
        AFTER_CONNECT;
    }
}
