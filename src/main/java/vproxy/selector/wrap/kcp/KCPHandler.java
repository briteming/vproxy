package vproxy.selector.wrap.kcp;

import vproxy.selector.wrap.arqudp.ArqUDPHandler;
import vproxy.selector.wrap.kcp.mock.ByteBuf;
import vproxy.util.ByteArray;
import vproxy.util.Logger;
import vproxy.util.nio.ByteArrayChannel;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

public class KCPHandler extends ArqUDPHandler {
    public static class KCPOptions {
        // config is copied from kcptun fast3
        // https://github.com/xtaci/kcptun/blob/master/server/main.go#L371

        public boolean nodelay = true;
        public int interval = 10;
        public int resend = 2;
        public boolean nc = true;

        public int sndWnd = 1024;
        public int rcvWnd = 1024;

        public int mtu = 1350;

        // the following are my configurations
        // decrease rto
        public int rxMinRto = 30;

        // alert the kcp every few ms
        public int clockInterval = 5;
    }

    private final Kcp kcp;
    private final KCPOptions opts;

    protected KCPHandler(Consumer<ByteArrayChannel> emitter, Object identifier, KCPOptions options) {
        super(emitter);
        this.kcp = new Kcp(0, (data, kcp) -> {
            assert Logger.lowLevelNetDebug("kcp wants to write " + data.chnl.used() + " bytes to " + kcp.getUser());
            assert Logger.lowLevelNetDebugPrintBytes(data.chnl.getBytes(), data.chnl.getReadOff(), data.chnl.getWriteOff());
            emitter.accept(data.chnl);
        });
        this.kcp.setUser(identifier);
        // configure kcp
        kcp.nodelay(options.nodelay, options.interval, options.resend, options.nc);
        kcp.wndsize(options.sndWnd, options.rcvWnd);
        kcp.setMtu(options.mtu);
        kcp.setRxMinrto(options.rxMinRto);
        this.opts = options;
    }

    @Override
    public ByteArray parse(ByteArrayChannel buf) throws IOException {
        int ret = kcp.input(new ByteBuf(buf));
        if (ret < 0) {
            throw new IOException("writing from network to kcp failed: " + ret);
        }

        ByteArray array = null;
        while (kcp.canRecv()) {
            List<ByteBuf> arrays = new LinkedList<>();
            ret = kcp.recv(arrays);
            if (ret <= 0) {
                break;
            }
            if (arrays.isEmpty()) {
                break;
            }
            for (ByteBuf b : arrays) {
                ByteArray a = b.chnl.readAll();
                if (array == null) {
                    array = a;
                } else {
                    array = array.concat(a);
                }
            }
        }
        return array;
    }

    @Override
    public void write(ByteArray input) throws IOException {
        int ret = kcp.send(new ByteBuf(ByteArrayChannel.fromFull(input)));
        if (ret < 0) {
            throw new IOException("writing from app to kcp failed: " + ret);
        }
    }

    @Override
    public void clock(long ts) throws IOException {
        kcp.update(ts);
        int state = kcp.getState();
        if (state < 0) {
            assert Logger.lowLevelDebug("kcp connection is invalid, state = " + state);
            throw new IOException("the kcp connection is invalid");
        }
    }

    @Override
    public int clockInterval() {
        return opts.clockInterval;
    }
}
