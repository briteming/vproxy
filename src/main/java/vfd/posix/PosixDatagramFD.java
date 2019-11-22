package vfd.posix;

import vfd.DatagramFD;
import vproxy.util.Tuple;
import vproxy.util.Utils;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

public class PosixDatagramFD extends PosixNetworkFD implements DatagramFD {
    private boolean bond = false;

    public PosixDatagramFD(Posix posix) {
        super(posix);
    }

    @Override
    protected int createIPv4FD() throws IOException {
        return posix.createIPv4UdpFD();
    }

    @Override
    protected int createIPv6FD() throws IOException {
        return posix.createIPv6UdpFD();
    }

    @Override
    public void connect(InetSocketAddress l4addr) throws IOException {
        super.connect(l4addr);
        connected = true;
    }

    @Override
    public void bind(InetSocketAddress l4addr) throws IOException {
        checkNotClosed();
        if (connected) {
            throw new IOException("is already connected");
        }
        if (bond) {
            throw new IOException("is already bond");
        }
        int port = l4addr.getPort();
        if (l4addr.getAddress() instanceof Inet4Address) {
            fd = posix.createIPv4UdpFD();
            finishConfigAfterFDCreated();
            ipv4 = true;
            int ipv4 = Utils.ipv4Bytes2Int(l4addr.getAddress().getAddress());
            posix.bindIPv4(fd, ipv4, port);
        } else if (l4addr.getAddress() instanceof Inet6Address) {
            fd = posix.createIPv6UdpFD();
            finishConfigAfterFDCreated();
            ipv4 = false;
            String ipv6 = Utils.ipStr(l4addr.getAddress().getAddress());
            posix.bindIPv6(fd, ipv6, port);
        } else {
            throw new IOException("unknown l3addr " + l4addr.getAddress());
        }
        bond = true;
    }

    @Override
    public int send(ByteBuffer buf, InetSocketAddress remote) throws IOException {
        if (connected) {
            throw new IOException("this fd is already connected");
        }
        checkNotClosed();
        if (fd == -1) {
            if (remote.getAddress() instanceof Inet4Address) {
                fd = createIPv4FD();
                ipv4 = true;
            } else {
                fd = createIPv6FD();
                ipv4 = false;
            }
        }
        if (ipv4) {
            if (!(remote.getAddress() instanceof Inet4Address)) {
                throw new IOException("unsupported address for this fd: " + remote);
            }
        } else {
            if (!(remote.getAddress() instanceof Inet6Address)) {
                throw new IOException("unsupported address for this fd: " + remote);
            }
        }
        int off = 0;
        int len = buf.limit() - buf.position();
        boolean release = false;
        ByteBuffer directBuffer;
        if (buf.isDirect()) {
            directBuffer = buf;
            off = buf.position();
        } else {
            directBuffer = ByteBuffer.allocateDirect(len);
            directBuffer.put(buf);
            release = true;
        }
        int n = 0;
        try {
            int port = remote.getPort();
            if (ipv4) {
                int ip = Utils.ipv4Bytes2Int(remote.getAddress().getAddress());
                n = posix.sendtoIPv4(fd, directBuffer, off, len, ip, port);
            } else {
                String ip = Utils.ipStr(remote.getAddress().getAddress());
                n = posix.sendtoIPv6(fd, directBuffer, off, len, ip, port);
            }
        } finally {
            if (release) { // src was fully read
                if (n < len) {
                    buf.position(buf.limit() - len + n);
                }
            } else { // src was not modified
                if (n > 0) {
                    buf.position(buf.position() + n);
                }
            }
            if (release) {
                Utils.clean(directBuffer);
            }
        }
        return n;
    }

    @Override
    public SocketAddress receive(ByteBuffer buf) throws IOException {
        checkFD();
        checkNotClosed();
        if (!bond) {
            throw new IOException("not bond");
        }
        int off = 0;
        int len = buf.limit() - buf.position();
        boolean release = false;
        ByteBuffer directBuffer;
        if (buf.isDirect()) {
            directBuffer = buf;
            off = buf.position();
        } else {
            directBuffer = ByteBuffer.allocateDirect(len);
            release = true;
        }
        VSocketAddress l4addr;
        int n = 0;
        try {
            Tuple<? extends VSocketAddress, Integer> tup;
            if (ipv4) {
                tup = posix.recvfromIPv4(fd, directBuffer, off, len);
            } else {
                tup = posix.recvfromIPv6(fd, directBuffer, off, len);
            }
            l4addr = tup.left;
            n = tup.right;
        } finally {
            if (n > 0) {
                if (release) {
                    directBuffer.limit(n).position(0);
                    directBuffer.put(buf);
                } else {
                    buf.position(buf.position() + n);
                }
            }
            if (release) {
                Utils.clean(directBuffer);
            }
        }
        return l4addr.toInetSocketAddress();
    }
}
