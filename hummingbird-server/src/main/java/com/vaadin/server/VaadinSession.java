/*
 * Copyright 2000-2016 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.vaadin.server;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;

import com.vaadin.shared.communication.PushMode;
import com.vaadin.ui.UI;
import com.vaadin.util.CurrentInstance;

/**
 * Contains everything that Vaadin needs to store for a specific user. This is
 * typically stored in a {@link HttpSession}, but others storage mechanisms
 * might also be used.
 * <p>
 * Everything inside a {@link VaadinSession} should be serializable to ensure
 * compatibility with schemes using serialization for persisting the session
 * data.
 *
 * @author Vaadin Ltd
 * @since 7.0.0
 */
public class VaadinSession implements HttpSessionBindingListener, Serializable {

    /**
     * Encapsulates a {@link Command} submitted using
     * {@link VaadinSession#access(Command)}. This class is used internally by
     * the framework and is not intended to be directly used by application
     * developers.
     *
     * @since 7.1
     * @author Vaadin Ltd
     */
    public static class FutureAccess extends FutureTask<Void> {
        /**
         * Snapshot of all non-inheritable current instances at the time this
         * object was created.
         */
        private final Map<Class<?>, CurrentInstance> instances = CurrentInstance
                .getInstances(true);
        private final VaadinSession session;
        private Command command;

        /**
         * Creates an instance for the given command.
         *
         * @param session
         *            the session to which the task belongs
         * @param command
         *            the command to run when this task is purged from the queue
         */
        public FutureAccess(VaadinSession session, Command command) {
            super(() -> command.execute(), null);
            this.session = session;
            this.command = command;
        }

        @Override
        public Void get() throws InterruptedException, ExecutionException {
            /*
             * Help the developer avoid programming patterns that cause
             * deadlocks unless implemented very carefully. get(long, TimeUnit)
             * does not have the same detection since a sensible timeout should
             * avoid completely locking up the application.
             *
             * Even though no deadlock could occur after the command has been
             * run, the check is always done as the deterministic behavior makes
             * it easier to detect potential problems.
             */
            VaadinService.verifyNoOtherSessionLocked(session);
            return super.get();
        }

        /**
         * Gets the current instance values that should be used when running
         * this task.
         *
         * @see CurrentInstance#restoreInstances(Map)
         *
         * @return a map of current instances.
         */
        public Map<Class<?>, CurrentInstance> getCurrentInstances() {
            return instances;
        }

        /**
         * Handles exceptions thrown during the execution of this task.
         *
         * @since 7.1.8
         * @param exception
         *            the thrown exception.
         */
        public void handleError(Exception exception) {
            try {
                if (command instanceof ErrorHandlingCommand) {
                    ErrorHandlingCommand errorHandlingCommand = (ErrorHandlingCommand) command;

                    errorHandlingCommand.handleError(exception);
                } else {
                    ErrorEvent errorEvent = new ErrorEvent(exception);

                    ErrorHandler errorHandler = ErrorEvent
                            .findErrorHandler(session);

                    if (errorHandler == null) {
                        errorHandler = new DefaultErrorHandler();
                    }

                    errorHandler.error(errorEvent);
                }
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, e.getMessage(), e);
            }
        }
    }

    /**
     * The lifecycle state of a VaadinSession.
     *
     * @since 7.2
     */
    public enum State {
        /**
         * The session is active and accepting client requests.
         */
        OPEN,
        /**
         * The {@link VaadinSession#close() close} method has been called; the
         * session will be closed as soon as the current request ends.
         */
        CLOSING,
        /**
         * The session is closed; all the {@link UI}s have been removed and
         * {@link SessionDestroyListener}s have been called.
         */
        CLOSED;

        private boolean isValidChange(State newState) {
            return (this == OPEN && newState == CLOSING)
                    || (this == CLOSING && newState == CLOSED);
        }
    }

    /**
     * The name of the parameter that is by default used in e.g. web.xml to
     * define the name of the default {@link UI} class.
     */
    // javadoc in UI should be updated if this value is changed
    public static final String UI_PARAMETER = "UI";

    /**
     * Configuration for the session.
     */
    private DeploymentConfiguration configuration;

    /**
     * Default locale of the session.
     */
    private Locale locale;

    /**
     * Session wide error handler which is used by default if an error is left
     * unhandled.
     */
    private ErrorHandler errorHandler = new DefaultErrorHandler();
    private LinkedList<RequestHandler> requestHandlers = new LinkedList<RequestHandler>();

    private int nextUIId = 0;
    private Map<Integer, UI> uIs = new HashMap<Integer, UI>();

    protected WebBrowser browser = new WebBrowser();

    private long cumulativeRequestDuration = 0;

    private long lastRequestDuration = -1;

    private long lastRequestTimestamp = System.currentTimeMillis();

    private State state = State.OPEN;

    private transient WrappedSession session;

    private final Map<String, Object> attributes = new HashMap<String, Object>();

    private transient VaadinService service;

    private transient Lock lock;

    /*
     * Pending tasks can't be serialized and the queue should be empty when the
     * session is serialized as long as it doesn't happen while some other
     * thread has the lock.
     */
    private transient ConcurrentLinkedQueue<FutureAccess> pendingAccessQueue = new ConcurrentLinkedQueue<FutureAccess>();

    private final String csrfToken = UUID.randomUUID().toString();

    private final StreamResourceRegistry resourceRegistry;

    /**
     * Creates a new VaadinSession tied to a VaadinService.
     *
     * @param service
     *            the Vaadin service for the new session
     */
    public VaadinSession(VaadinService service) {
        this.service = service;
        resourceRegistry = new StreamResourceRegistry(this);
    }

    /**
     * @see javax.servlet.http.HttpSessionBindingListener#valueBound(HttpSessionBindingEvent)
     */
    @Override
    public void valueBound(HttpSessionBindingEvent arg0) {
        // We are not interested in bindings
    }

    /**
     * @see javax.servlet.http.HttpSessionBindingListener#valueUnbound(HttpSessionBindingEvent)
     */
    @Override
    public void valueUnbound(HttpSessionBindingEvent event) {
        // If we are going to be unbound from the session, the session must be
        // closing
        // Notify the service
        if (service == null) {
            getLogger().warning(
                    "A VaadinSession instance not associated to any service is getting unbound. "
                            + "Session destroy events will not be fired and UIs in the session will not get detached. "
                            + "This might happen if a session is deserialized but never used before it expires.");
        } else if (VaadinService.getCurrentRequest() != null
                && getCurrent() == this) {
            assert hasLock();
            // Ignore if the session is being moved to a different backing
            // session or if GAEVaadinServlet is doing its normal cleanup.
            if (getAttribute(
                    VaadinService.PRESERVE_UNBOUND_SESSION_ATTRIBUTE) == Boolean.TRUE) {
                return;
            }

            // There is still a request in progress for this session. The
            // session will be destroyed after the response has been written.
            if (getState() == State.OPEN) {
                close();
            }
        } else {
            // We are not in a request related to this session so we can destroy
            // it as soon as we acquire the lock.
            service.fireSessionDestroy(this);
        }
        session = null;
    }

    /**
     * Get the web browser associated with this session.
     *
     * @return the web browser object
     */
    public WebBrowser getBrowser() {
        assert hasLock();
        return browser;
    }

    /**
     * @return The total time spent servicing requests in this session, in
     *         milliseconds.
     */
    public long getCumulativeRequestDuration() {
        assert hasLock();
        return cumulativeRequestDuration;
    }

    /**
     * Sets the time spent servicing the last request in the session and updates
     * the total time spent servicing requests in this session.
     *
     * @param time
     *            The time spent in the last request, in milliseconds.
     */
    public void setLastRequestDuration(long time) {
        assert hasLock();
        lastRequestDuration = time;
        cumulativeRequestDuration += time;
    }

    /**
     * @return The time spent servicing the last request in this session, in
     *         milliseconds.
     */
    public long getLastRequestDuration() {
        assert hasLock();
        return lastRequestDuration;
    }

    /**
     * Sets the time when the last UIDL request was serviced in this session.
     *
     * @param timestamp
     *            The time when the last request was handled, in milliseconds
     *            since the epoch.
     *
     */
    public void setLastRequestTimestamp(long timestamp) {
        assert hasLock();
        lastRequestTimestamp = timestamp;
    }

    /**
     * Returns the time when the last request was serviced in this session.
     *
     * @return The time when the last request was handled, in milliseconds since
     *         the epoch.
     */
    public long getLastRequestTimestamp() {
        assert hasLock();
        return lastRequestTimestamp;
    }

    /**
     * Gets the underlying session to which this service session is currently
     * associated.
     *
     * @return the wrapped session for this context
     */
    public WrappedSession getSession() {
        /*
         * This is used to fetch the underlying session and there is no need for
         * having a lock when doing this. On the contrary this is sometimes done
         * to be able to lock the session.
         */
        return session;
    }

    /**
     * Retrieves all {@link VaadinSession}s which are stored in the given HTTP
     * session
     *
     * @since 7.2
     * @param httpSession
     *            the HTTP session
     * @return the found VaadinSessions
     */
    public static Collection<VaadinSession> getAllSessions(
            HttpSession httpSession) {
        Set<VaadinSession> sessions = new HashSet<VaadinSession>();
        Enumeration<String> attributeNames = httpSession.getAttributeNames();

        while (attributeNames.hasMoreElements()) {
            String attributeName = attributeNames.nextElement();
            if (attributeName.startsWith(VaadinSession.class.getName() + ".")) {
                Object value = httpSession.getAttribute(attributeName);
                if (value instanceof VaadinSession) {
                    sessions.add((VaadinSession) value);
                }
            }
        }
        return sessions;
    }

    /**
     * Updates the transient session lock from VaadinService.
     */
    private void refreshLock() {
        assert lock == null || lock == service.getSessionLock(
                session) : "Cannot change the lock from one instance to another";
        assert hasLock(service, session);
        lock = service.getSessionLock(session);
    }

    public void setConfiguration(DeploymentConfiguration configuration) {
        assert hasLock();
        if (configuration == null) {
            throw new IllegalArgumentException("Can not set to null");
        }
        assert this.configuration == null : "Configuration can only be set once";
        this.configuration = configuration;
    }

    /**
     * Gets the configuration for this session.
     *
     * @return the deployment configuration
     */
    public DeploymentConfiguration getConfiguration() {
        assert hasLock();
        return configuration;
    }

    /**
     * Gets the default locale for this session.
     *
     * By default this is the preferred locale of the user using the session. In
     * most cases it is read from the browser defaults.
     *
     * @return the locale of this session.
     */
    public Locale getLocale() {
        assert hasLock();
        if (locale != null) {
            return locale;
        }
        return Locale.getDefault();
    }

    /**
     * Sets the default locale for this session.
     *
     * By default this is the preferred locale of the user using the
     * application. In most cases it is read from the browser defaults.
     *
     * @param locale
     *            the Locale object.
     *
     */
    public void setLocale(Locale locale) {
        assert hasLock();
        this.locale = locale;
    }

    /**
     * Gets the session's error handler.
     *
     * @return the current error handler
     */
    public ErrorHandler getErrorHandler() {
        assert hasLock();
        return errorHandler;
    }

    /**
     * Sets the session error handler.
     *
     * @param errorHandler
     */
    public void setErrorHandler(ErrorHandler errorHandler) {
        assert hasLock();
        this.errorHandler = errorHandler;
    }

    /**
     * Adds a request handler to this session. Request handlers can be added to
     * provide responses to requests that are not handled by the default
     * functionality of the framework.
     * <p>
     * Handlers are called in reverse order of addition, so the most recently
     * added handler will be called first.
     *
     * @param handler
     *            the request handler to add
     *
     * @see #removeRequestHandler(RequestHandler)
     *
     * @since 7.0
     */
    public void addRequestHandler(RequestHandler handler) {
        assert hasLock();
        requestHandlers.addFirst(handler);
    }

    /**
     * Removes a request handler from the session.
     *
     * @param handler
     *            the request handler to remove
     *
     * @since 7.0
     */
    public void removeRequestHandler(RequestHandler handler) {
        assert hasLock();
        requestHandlers.remove(handler);
    }

    /**
     * Gets the request handlers that are registered to the session. The
     * iteration order of the returned collection is the same as the order in
     * which the request handlers will be invoked when a request is handled.
     *
     * @return a collection of request handlers, with the iteration order
     *         according to the order they would be invoked
     *
     * @see #addRequestHandler(RequestHandler)
     * @see #removeRequestHandler(RequestHandler)
     *
     * @since 7.0
     */
    public Collection<RequestHandler> getRequestHandlers() {
        assert hasLock();
        return Collections.unmodifiableCollection(requestHandlers);
    }

    /**
     * Gets the currently used session. The current session is automatically
     * defined when processing requests to the server and in threads started at
     * a point when the current session is defined (see
     * {@link InheritableThreadLocal}). In other cases, (e.g. from background
     * threads started in some other way), the current session is not
     * automatically defined.
     * <p>
     * The session is stored using a weak reference to avoid leaking memory in
     * case it is not explicitly cleared.
     *
     * @return the current session instance if available, otherwise
     *         <code>null</code>
     *
     * @see #setCurrent(VaadinSession)
     *
     * @since 7.0
     */
    public static VaadinSession getCurrent() {
        return CurrentInstance.get(VaadinSession.class);
    }

    /**
     * Sets the thread local for the current session. This method is used by the
     * framework to set the current session whenever a new request is processed
     * and it is cleared when the request has been processed.
     * <p>
     * The application developer can also use this method to define the current
     * session outside the normal request handling and treads started from
     * request handling threads, e.g. when initiating custom background threads.
     * <p>
     * The session is stored using a weak reference to avoid leaking memory in
     * case it is not explicitly cleared.
     *
     * @param session
     *            the session to set as current
     *
     * @see #getCurrent()
     * @see ThreadLocal
     *
     * @since 7.0
     */
    public static void setCurrent(VaadinSession session) {
        CurrentInstance.setInheritable(VaadinSession.class, session);
    }

    /**
     * Gets all the UIs of this session. This includes UIs that have been
     * requested but not yet initialized. UIs that receive no heartbeat requests
     * from the client are eventually removed from the session.
     *
     * @return a collection of UIs belonging to this application
     *
     * @since 7.0
     */
    public Collection<UI> getUIs() {
        assert hasLock();
        return Collections.unmodifiableCollection(uIs.values());
    }

    /**
     * Returns a UI with the given id.
     * <p>
     * This is meant for framework internal use.
     *
     * @param uiId
     *            The UI id
     * @return The UI with the given id or null if not found
     */
    public UI getUIById(int uiId) {
        assert hasLock();
        return uIs.get(uiId);
    }

    /**
     * Checks if the current thread has exclusive access to this VaadinSession
     *
     * @return true if the thread has exclusive access, false otherwise
     * @since 7.1
     */
    public boolean hasLock() {
        ReentrantLock l = ((ReentrantLock) getLockInstance());
        return l.isHeldByCurrentThread();
    }

    /**
     * Checks if the current thread has exclusive access to the given
     * WrappedSession.
     *
     * @return true if this thread has exclusive access, false otherwise
     * @since 7.6
     */
    protected static boolean hasLock(VaadinService service,
            WrappedSession session) {
        ReentrantLock l = (ReentrantLock) service.getSessionLock(session);
        return l.isHeldByCurrentThread();
    }

    /**
     * Called by the framework to remove an UI instance from the session because
     * it has been closed.
     *
     * @param ui
     *            the UI to remove
     */
    public void removeUI(UI ui) {
        assert hasLock();
        assert UI.getCurrent() == ui;
        Integer id = Integer.valueOf(ui.getUIId());
        ui.setSession(null);
        uIs.remove(id);
    }

    /**
     * Gets the {@link Lock} instance that is used for protecting the data of
     * this session from concurrent access.
     * <p>
     * The <code>Lock</code> can be used to gain more control than what is
     * available only using {@link #lock()} and {@link #unlock()}. The returned
     * instance is not guaranteed to support any other features of the
     * <code>Lock</code> interface than {@link Lock#lock()} and
     * {@link Lock#unlock()}.
     *
     * @return the <code>Lock</code> that is used for synchronization, never
     *         <code>null</code>
     *
     * @see #lock()
     * @see Lock
     */
    public Lock getLockInstance() {
        return lock;
    }

    /**
     * Locks this session to protect its data from concurrent access. Accessing
     * the UI state from outside the normal request handling should always lock
     * the session and unlock it when done. The preferred way to ensure locking
     * is done correctly is to wrap your code using {@link UI#access(Command)}
     * (or {@link VaadinSession#access(Command)} if you are only touching the
     * session and not any UI), e.g.:
     *
     * <pre>
     * myUI.access(new Command() {
     *     &#064;Override
     *     public void run() {
     *         // Here it is safe to update the UI.
     *         // UI.getCurrent can also be used
     *         myUI.getContent().setCaption(&quot;Changed safely&quot;);
     *     }
     * });
     * </pre>
     *
     * If you for whatever reason want to do locking manually, you should do it
     * like:
     *
     * <pre>
     * session.lock();
     * try {
     *     doSomething();
     * } finally {
     *     session.unlock();
     * }
     * </pre>
     *
     * This method will block until the lock can be retrieved.
     * <p>
     * {@link #getLockInstance()} can be used if more control over the locking
     * is required.
     *
     * @see #unlock()
     * @see #getLockInstance()
     * @see #hasLock()
     */
    public void lock() {
        getLockInstance().lock();
    }

    /**
     * Unlocks this session. This method should always be used in a finally
     * block after {@link #lock()} to ensure that the lock is always released.
     * <p>
     * For UIs in this session that have its push mode set to
     * {@link PushMode#AUTOMATIC automatic}, pending changes will be pushed to
     * their respective clients.
     *
     * @see #lock()
     * @see UI#push()
     */
    public void unlock() {
        assert hasLock();
        boolean ultimateRelease = false;
        try {
            /*
             * Run pending tasks and push if the reentrant lock will actually be
             * released by this unlock() invocation.
             */
            if (((ReentrantLock) getLockInstance()).getHoldCount() == 1) {
                ultimateRelease = true;
                getService().runPendingAccessTasks(this);

                for (UI ui : getUIs()) {
                    if (ui.getPushConfiguration()
                            .getPushMode() == PushMode.AUTOMATIC) {
                        Map<Class<?>, CurrentInstance> oldCurrent = CurrentInstance
                                .setCurrent(ui);
                        try {
                            ui.push();
                        } finally {
                            CurrentInstance.restoreInstances(oldCurrent);
                        }
                    }
                }
            }
        } finally {
            getLockInstance().unlock();
        }

        /*
         * If the session is locked when a new access task is added, it is
         * assumed that the queue will be purged when the lock is released. This
         * might however not happen if a task is enqueued between the moment
         * when unlock() purges the queue and the moment when the lock is
         * actually released. This means that the queue should be purged again
         * if it is not empty after unlocking.
         */
        if (ultimateRelease && !getPendingAccessQueue().isEmpty()) {
            getService().ensureAccessQueuePurged(this);
        }
    }

    /**
     * Stores a value in this service session. This can be used to associate
     * data with the current user so that it can be retrieved at a later point
     * from some other part of the application. Setting the value to
     * <code>null</code> clears the stored value.
     *
     * @see #getAttribute(String)
     *
     * @param name
     *            the name to associate the value with, can not be
     *            <code>null</code>
     * @param value
     *            the value to associate with the name, or <code>null</code> to
     *            remove a previous association.
     */
    public void setAttribute(String name, Object value) {
        assert hasLock();
        if (name == null) {
            throw new IllegalArgumentException("name can not be null");
        }
        if (value != null) {
            attributes.put(name, value);
        } else {
            attributes.remove(name);
        }
    }

    /**
     * Stores a value in this service session. This can be used to associate
     * data with the current user so that it can be retrieved at a later point
     * from some other part of the application. Setting the value to
     * <code>null</code> clears the stored value.
     * <p>
     * The fully qualified name of the type is used as the name when storing the
     * value. The outcome of calling this method is thus the same as if calling
     * <p>
     * <code>setAttribute(type.getName(), value);</code>
     *
     * @see #getAttribute(Class)
     * @see #setAttribute(String, Object)
     *
     * @param type
     *            the type that the stored value represents, can not be null
     * @param value
     *            the value to associate with the type, or <code>null</code> to
     *            remove a previous association.
     */
    public <T> void setAttribute(Class<T> type, T value) {
        assert hasLock();
        if (type == null) {
            throw new IllegalArgumentException("type can not be null");
        }
        if (value != null && !type.isInstance(value)) {
            throw new IllegalArgumentException("value of type " + type.getName()
                    + " expected but got " + value.getClass().getName());
        }
        setAttribute(type.getName(), value);
    }

    /**
     * Gets a stored attribute value. If a value has been stored for the
     * session, that value is returned. If no value is stored for the name,
     * <code>null</code> is returned.
     *
     * @see #setAttribute(String, Object)
     *
     * @param name
     *            the name of the value to get, can not be <code>null</code>.
     * @return the value, or <code>null</code> if no value has been stored or if
     *         it has been set to null.
     */
    public Object getAttribute(String name) {
        assert hasLock();
        if (name == null) {
            throw new IllegalArgumentException("name can not be null");
        }
        return attributes.get(name);
    }

    /**
     * Gets a stored attribute value. If a value has been stored for the
     * session, that value is returned. If no value is stored for the name,
     * <code>null</code> is returned.
     * <p>
     * The fully qualified name of the type is used as the name when getting the
     * value. The outcome of calling this method is thus the same as if calling
     * <br>
     * <br>
     * <code>getAttribute(type.getName());</code>
     *
     * @see #setAttribute(Class, Object)
     * @see #getAttribute(String)
     *
     * @param type
     *            the type of the value to get, can not be <code>null</code>.
     * @return the value, or <code>null</code> if no value has been stored or if
     *         it has been set to null.
     */
    public <T> T getAttribute(Class<T> type) {
        assert hasLock();
        if (type == null) {
            throw new IllegalArgumentException("type can not be null");
        }
        Object value = getAttribute(type.getName());
        if (value == null) {
            return null;
        } else {
            return type.cast(value);
        }
    }

    /**
     * Creates a new unique id for a UI.
     *
     * @return a unique UI id
     */
    public int getNextUIid() {
        assert hasLock();
        return nextUIId++;
    }

    /**
     * Adds an initialized UI to this session.
     *
     * @param ui
     *            the initialized UI to add.
     */
    public void addUI(UI ui) {
        assert hasLock();
        if (ui.getUIId() == -1) {
            throw new IllegalArgumentException(
                    "Can not add an UI that has not been initialized.");
        }
        if (ui.getSession() != this) {
            throw new IllegalArgumentException(
                    "The UI belongs to a different session");
        }

        Integer uiId = Integer.valueOf(ui.getUIId());
        uIs.put(uiId, ui);
    }

    public VaadinService getService() {
        return service;
    }

    /**
     * Sets this session to be closed and all UI state to be discarded at the
     * end of the current request, or at the end of the next request if there is
     * no ongoing one.
     * <p>
     * After the session has been discarded, any UIs that have been left open
     * will give a Session Expired error and a new session will be created for
     * serving new UIs.
     *
     * @see SystemMessages#getSessionExpiredCaption()
     *
     */
    public void close() {
        assert hasLock();
        state = State.CLOSING;
    }

    /**
     * Returns the lifecycle state of this session.
     *
     * @since 7.2
     * @return the current state
     */
    public State getState() {
        assert hasLock();
        return state;
    }

    /**
     * Sets the lifecycle state of this session. The allowed transitions are
     * OPEN to CLOSING and CLOSING to CLOSED.
     *
     * @since 7.2
     * @param state
     *            the new state
     */
    protected void setState(State state) {
        assert hasLock();
        assert this.state.isValidChange(state) : "Invalid session state change "
                + this.state + "->" + state;

        this.state = state;
    }

    private static final Logger getLogger() {
        return Logger.getLogger(VaadinSession.class.getName());
    }

    /**
     * Locks this session and runs the provided Command right away.
     * <p>
     * It is generally recommended to use {@link #access(Command)} instead of
     * this method for accessing a session from a different thread as
     * {@link #access(Command)} can be used while holding the lock of another
     * session. To avoid causing deadlocks, this methods throws an exception if
     * it is detected than another session is also locked by the current thread.
     * <p>
     * This method behaves differently than {@link #access(Command)} in some
     * situations:
     * <ul>
     * <li>If the current thread is currently holding the lock of this session,
     * {@link #accessSynchronously(Command)} runs the task right away whereas
     * {@link #access(Command)} defers the task to a later point in time.</li>
     * <li>If some other thread is currently holding the lock for this session,
     * {@link #accessSynchronously(Command)} blocks while waiting for the lock
     * to be available whereas {@link #access(Command)} defers the task to a
     * later point in time.</li>
     * </ul>
     *
     * @param command
     *            the command which accesses the session
     *
     * @throws IllegalStateException
     *             if the current thread holds the lock for another session
     *
     * @since 7.1
     *
     * @see #lock()
     * @see #getCurrent()
     * @see #access(Command)
     * @see UI#accessSynchronously(Command)
     */
    public void accessSynchronously(Command command) {
        VaadinService.verifyNoOtherSessionLocked(this);

        Map<Class<?>, CurrentInstance> old = null;
        lock();
        try {
            old = CurrentInstance.setCurrent(this);
            command.execute();
        } finally {
            unlock();
            if (old != null) {
                CurrentInstance.restoreInstances(old);
            }
        }

    }

    /**
     * Provides exclusive access to this session from outside a request handling
     * thread.
     * <p>
     * The given command is executed while holding the session lock to ensure
     * exclusive access to this session. If this session is not locked, the lock
     * will be acquired and the command is run right away. If this session is
     * currently locked, the command will be run before that lock is released.
     * <p>
     * RPC handlers for components inside this session do not need to use this
     * method as the session is automatically locked by the framework during RPC
     * handling.
     * <p>
     * Please note that the command might be invoked on a different thread or
     * later on the current thread, which means that custom thread locals might
     * not have the expected values when the command is executed. Inheritable
     * values in {@link CurrentInstance} will have the same values as when this
     * method was invoked. {@link VaadinSession#getCurrent()} and
     * {@link VaadinService#getCurrent()} are set according to this session
     * before executing the command. Non-inheritable CurrentInstance values
     * including {@link VaadinService#getCurrentRequest()} and
     * {@link VaadinService#getCurrentResponse()} will not be defined.
     * <p>
     * The returned future can be used to check for task completion and to
     * cancel the task. To help avoiding deadlocks, {@link Future#get()} throws
     * an exception if it is detected that the current thread holds the lock for
     * some other session.
     *
     * @see #lock()
     * @see #getCurrent()
     * @see #accessSynchronously(Command)
     * @see UI#access(Command)
     *
     * @since 7.1
     *
     * @param command
     *            the command which accesses the session
     * @return a future that can be used to check for task completion and to
     *         cancel the task
     */
    public Future<Void> access(Command command) {
        return getService().accessSession(this, command);
    }

    /**
     * Gets the queue of tasks submitted using {@link #access(Command)}. It is
     * safe to call this method and access the returned queue without holding
     * the {@link #lock() session lock}.
     *
     * @since 7.1
     *
     * @return the queue of pending access tasks
     */
    public Queue<FutureAccess> getPendingAccessQueue() {
        return pendingAccessQueue;
    }

    /**
     * Gets the CSRF token (aka double submit cookie) that is used to protect
     * against Cross Site Request Forgery attacks.
     *
     * @since 7.1
     * @return the csrf token string
     */
    public String getCsrfToken() {
        assert hasLock();
        return csrfToken;
    }

    /**
     * Override default deserialization logic to account for transient
     * {@link #pendingAccessQueue}.
     */
    private void readObject(ObjectInputStream stream)
            throws IOException, ClassNotFoundException {
        Map<Class<?>, CurrentInstance> old = CurrentInstance.setCurrent(this);
        try {
            stream.defaultReadObject();
            pendingAccessQueue = new ConcurrentLinkedQueue<FutureAccess>();
        } finally {
            CurrentInstance.restoreInstances(old);
        }
    }

    /**
     * Refreshes the transient fields of the session to ensure they are up to
     * date.
     * <p>
     * Called internally by the framework.
     *
     * @since 7.6
     * @param wrappedSession
     *            the session this VaadinSession is stored in
     * @param vaadinService
     *            the service associated with this VaadinSession
     */
    public void refreshTransients(WrappedSession wrappedSession,
            VaadinService vaadinService) {
        session = wrappedSession;
        service = vaadinService;
        refreshLock();
    }

    /**
     * Get resource registry instance.
     * <p>
     * Use this instance to manage {@link StreamResource}s.
     * 
     * @return resource registry
     */
    public StreamResourceRegistry getResourceRegistry() {
        return resourceRegistry;
    }
}
