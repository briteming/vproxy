package net.cassite.vproxy.selector;

import net.cassite.vproxy.util.*;

import java.io.IOException;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class SelectorEventLoop {
    static class RegisterData {
        Handler handler;
        Object att;
    }

    private final Selector selector;
    private final TimeQueue<Runnable> timeQueue = new TimeQueue<>();
    private final ConcurrentLinkedQueue<Runnable> runOnLoopEvents = new ConcurrentLinkedQueue<>();
    private final HandlerContext ctx = new HandlerContext(this); // always reuse the ctx object

    // these locks are a little tricky
    // see comments in loop() and close()
    private final Object CLOSE_LOCK = new Object();
    private List<Tuple<SelectableChannel, RegisterData>> THE_KEY_SET_BEFORE_SELECTOR_CLOSE;
    // see comments in add() and loop()
    private final Object REG_LOCK = new Object();

    private SelectorEventLoop() throws IOException {
        this.selector = Selector.open();
    }

    public static SelectorEventLoop open() throws IOException {
        return new SelectorEventLoop();
    }

    private void tryRunnable(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            // we cannot throw the error, just log
            Logger.error(LogType.IMPROPER_USE, "exception thrown in nextTick event ", t);
        }
    }

    private void handleNonSelectEvents() {
        handleRunOnLoopEvents();
        handleTimeEvents();
    }

    private void handleRunOnLoopEvents() {
        Runnable r;
        List<Runnable> toRun = new LinkedList<>();
        while ((r = runOnLoopEvents.poll()) != null) {
            toRun.add(r);
        }
        for (Runnable rr : toRun) {
            tryRunnable(rr);
        }
    }

    private void handleTimeEvents() {
        List<Runnable> toRun = new LinkedList<>();
        while (timeQueue.nextTime() == 0) {
            Runnable r = timeQueue.pop();
            toRun.add(r);
        }
        for (Runnable r : toRun) {
            tryRunnable(r);
        }
    }

    @SuppressWarnings("unchecked")
    private void doHandling(Iterator<SelectionKey> keys) {
        while (keys.hasNext()) {
            SelectionKey key = keys.next();
            keys.remove();

            RegisterData registerData = (RegisterData) key.attachment();

            SelectableChannel channel = key.channel();
            Handler handler = registerData.handler;

            ctx.channel = channel;
            ctx.attachment = registerData.att;

            if (!key.isValid()) {
                //noinspection UnnecessaryContinue
                continue;
            } else if (!channel.isOpen()) {
                Logger.stderr("channel is closed but still firing");
            } else {
                int readyOps = key.readyOps();
                if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                    try {
                        handler.connected(ctx);
                    } catch (Throwable t) {
                        Logger.error(LogType.IMPROPER_USE, "the connected callback got exception", t);
                    }
                } else if ((readyOps & SelectionKey.OP_ACCEPT) != 0) {
                    try {
                        handler.accept(ctx);
                    } catch (Throwable t) {
                        Logger.error(LogType.IMPROPER_USE, "the accept callback got exception", t);
                    }
                } else if ((readyOps & SelectionKey.OP_READ) != 0) {
                    try {
                        handler.readable(ctx);
                    } catch (Throwable t) {
                        Logger.error(LogType.IMPROPER_USE, "the readable callback got exception", t);
                    }
                } else {
                    assert (readyOps & SelectionKey.OP_WRITE) != 0;
                    try {
                        handler.writable(ctx);
                    } catch (Throwable t) {
                        Logger.error(LogType.IMPROPER_USE, "the writable callback got exception", t);
                    }
                }
            }
        }
    }

    private void release() {
        for (Tuple<SelectableChannel, RegisterData> tuple : THE_KEY_SET_BEFORE_SELECTOR_CLOSE) {
            SelectableChannel channel = tuple.a;
            RegisterData att = tuple.b;
            triggerRemovedCallback(channel, att);
        }
    }

    public void loop() {
        while (selector.isOpen()) {
            synchronized (CLOSE_LOCK) {
                // yes, we lock the whole while body (except the select part)
                // it's ok because we won't close the loop from inside the loop
                // and it's also ok to let the operator wait for some time
                // since closing the loop is considered as expansive and dangerous

                if (!selector.isOpen())
                    break; // break if it's closed

                // handle some non select events
                timeQueue.setCurrent(System.currentTimeMillis());
                handleNonSelectEvents();
            }
            // here we do not lock select()
            // let close() have chance to run

            final int selectedSize;
            try {
                if (timeQueue.isEmpty()) {
                    selectedSize = selector.select(); // always sleep
                } else {
                    selectedSize = selector.select(timeQueue.nextTime());
                }
            } catch (IOException e) {
                // let's ignore this exception and continue
                // if it's closed, the next loop will not run
                continue;
            }

            // we lock the REG_LOCK
            // to make sure the add() is finished
            // and the selectionKeys will be working in the next loop
            synchronized (REG_LOCK) { // do nothing, just wait for lock to release
            }

            // here we lock again
            // because we need to handle something
            // and at this time the selector might be closed
            synchronized (CLOSE_LOCK) {

                if (!selector.isOpen())
                    break; // break if it's closed

                if (selectedSize > 0) {
                    Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                    doHandling(keys);
                }
            }
            // while-loop ends here
        }
        // do the final release
        release();
    }

    @ThreadSafe
    public void nextTick(Runnable r) {
        runOnLoopEvents.add(r);
        selector.wakeup(); // wake the selector because new event is added
    }

    @ThreadSafe
    public TimerEvent delay(int timeout, Runnable r) {
        TimerEvent e = new TimerEvent(this);
        // timeQueue is not thread safe
        // modify it in the event loop's thread
        nextTick(() -> e.setEvent(timeQueue.push(timeout, r)));
        return e;
    }

    @ThreadSafe
    @SuppressWarnings("DuplicateThrows")
    public <CHANNEL extends SelectableChannel> void add(CHANNEL channel, int ops, Object attachment, Handler<CHANNEL> handler) throws ClosedChannelException, IOException {
        channel.configureBlocking(false);
        RegisterData registerData = new RegisterData();
        registerData.att = attachment;
        registerData.handler = handler;
        synchronized (REG_LOCK) { // lock it to make sure register is done
            selector.wakeup();
            channel.register(selector, ops, registerData);
        }
    }

    @ThreadSafe
    public void modify(SelectableChannel channel, int ops) {
        SelectionKey key = getKeyCheckNull(channel);
        key.interestOps(ops);
    }

    @ThreadSafe
    public void addOps(SelectableChannel channel, int ops) {
        SelectionKey key = getKeyCheckNull(channel);
        key.interestOps(key.interestOps() | ops);
    }

    @ThreadSafe
    public void rmOps(SelectableChannel channel, int ops) {
        SelectionKey key = getKeyCheckNull(channel);
        key.interestOps(key.interestOps() & ~ops);
    }

    @ThreadSafe
    public void remove(SelectableChannel channel) {
        SelectionKey key;
        // synchronize the channel
        // to prevent it being canceled from multiple threads
        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized (channel) {
            key = channel.keyFor(selector);
            if (key == null)
                return;
        }
        RegisterData att = (RegisterData) key.attachment();
        key.cancel();
        triggerRemovedCallback(channel, att);
    }

    @ThreadSafe
    public int getOps(SelectableChannel channel) {
        SelectionKey key = getKeyCheckNull(channel);
        return key.interestOps();
    }

    private SelectionKey getKeyCheckNull(SelectableChannel channel) {
        SelectionKey key = channel.keyFor(selector);
        if (key == null)
            throw new IllegalArgumentException("channel is not registered with this selector");
        return key;
    }

    @SuppressWarnings("unchecked")
    private void triggerRemovedCallback(SelectableChannel channel, RegisterData registerData) {
        assert registerData != null;
        ctx.channel = channel;
        ctx.attachment = registerData.att;
        try {
            registerData.handler.removed(ctx);
        } catch (Throwable t) {
            Logger.error(LogType.IMPROPER_USE, "the removed callback got exception", t);
        }
    }

    @ThreadSafe
    public boolean isClosed() {
        return !selector.isOpen();
    }

    @Blocking
    @ThreadSafe
    public void close() throws IOException {
        synchronized (CLOSE_LOCK) {
            Set<SelectionKey> keys = selector.keys();
            THE_KEY_SET_BEFORE_SELECTOR_CLOSE = new ArrayList<>(keys.size());
            for (SelectionKey key : keys) {
                THE_KEY_SET_BEFORE_SELECTOR_CLOSE.add(new Tuple<>(key.channel(), (RegisterData) key.attachment()));
            }
            selector.close();
        }
    }
}
