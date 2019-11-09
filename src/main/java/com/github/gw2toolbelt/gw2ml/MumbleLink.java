/*
 * Copyright (c) 2019 Leon Linhart
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
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.github.gw2toolbelt.gw2ml;

import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import javax.annotation.Nullable;

/**
 * A {@link MumbleLink} object serves as a view for the data provided by a Guild Wars 2 game client via the MumbleLink
 * mechanism. An instance can be obtained by calling {@link #open()} - the primary entry point of GW2ML.
 *
 * <p>{@link Configuration} may be used to further configure the behavior of the initialization of this class.</p>
 *
 * @see <a href="https://wiki.mumble.info/wiki/Link">Mumble Wiki</a>
 * @see <a href="https://wiki.guildwars2.com/wiki/API:MumbleLink">Guild Wars 2 Wiki</a>
 *
 * @since   0.1.0
 *
 * @author  Leon Linhart
 */
public final class MumbleLink implements AutoCloseable {

    static final String GW2ML_VERSION = apiGetManifestValue("Implementation-Version").orElse("dev");

    private static final String JNI_LIBRARY_NAME = Configuration.LIBRARY_NAME.get(Platform.mapLibraryNameBundled("gw2ml"));

    static {
        JNILibraryLoader.loadSystem("com.github.gw2toolbelt.gw2ml", JNI_LIBRARY_NAME);
    }

    private static final AtomicInteger refCounter = new AtomicInteger(0);

    @Nullable
    private static MumbleLink instance;

    /**
     * Opens a {@link MumbleLink view} of the data provided by Guild Wars 2 via the MumbleLink mechanism.
     *
     * <p>The object returned by this method may not be unique, and may make use of reference-counting mechanisms.
     * Additionally, it is not guaranteed that the returned object becomes {@link #isClosed()} invalid after calling
     * {@link #close()}.</p>
     *
     * <p>It is recommended to open a {@code MumbleLink} object once and keep it around for the lifetime of the
     * application when possible.</p>
     *
     * @return  a {@code MumbleLink} object that may be used to read the data provided by Guild Wars 2 via the
     *          MumbleLink mechanism
     *
     * @implNote    For better performance this implementation reuses MumbleLink objects whenever possible. In practice
     *              this means that closing a MumbleLink object might not have an immediate effect which in turn results
     *              into the object still being valid. However, once close has been invoked on a reference to an object,
     *              that reference should be discarded as quickly as possible since the underlying object may be
     *              invalidated at any time by another thread.
     *
     * @since   0.1.0
     */
    public static MumbleLink open() {
        int refCount = refCounter.getAndIncrement();
        if (refCount < 0) throw new IllegalStateException("This GW2ML implementation does not support having more than 2147483647 open views.");

        if (refCount == 0) instance = nOpen();

        assert (instance != null);
        return instance;
    }

    private static native MumbleLink nOpen();

    /**
     * Returns the value of the specified manifest attribute in the JAR file.
     *
     * @param attributeName the attribute name
     *
     * @return  the attribute value or null if the attribute was not found or there is no JAR file
     */
    private static Optional<String> apiGetManifestValue(String attributeName) {
        URL url = MumbleLink.class.getClassLoader().getResource("com/github/gw2toolbelt/gw2ml/MumbleLink.class");

        if (url != null) {
            String classURL = url.toString();

            if (classURL.startsWith("jar:")) {
                try (InputStream stream = new URL(classURL.substring(0, classURL.lastIndexOf('!') + 1) + '/' + JarFile.MANIFEST_NAME).openStream()) {
                    return Optional.ofNullable(new Manifest(stream).getMainAttributes().getValue(attributeName));
                } catch (Exception e) {
                    e.printStackTrace(JNILibraryLoader.DEBUG_STREAM);
                }
            }
        }

        return Optional.empty();
    }

    private final Context context = new Context();

    private long address;
    private final ByteBuffer data;

    private MumbleLink(long address, ByteBuffer data) {
        this.address = address;
        this.data = data.order(ByteOrder.nativeOrder());
    }

    /**
     * Closes this resource.
     *
     * @implNote    For better performance this implementation reuses MumbleLink objects whenever possible. In practice
     *              this means that closing a MumbleLink object might not have an immediate effect which in turn results
     *              into the object still being valid. However, once close has been invoked on a reference to an object,
     *              that reference should be discarded as quickly as possible since the underlying object may be
     *              invalidated at any time by another thread.
     *
     * @since   0.1.0
     */
    @Override
    public void close() {
        if (refCounter.decrementAndGet() == 0) {
            nClose(this.address);

            this.address = 0;
        }
    }

    private static native void nClose(long address);

    /**
     * Returns whether or not this object is invalid.
     *
     * @return  whether or not this object is invalid
     *
     * @see #close()
     *
     * @since   0.1.0
     */
    public boolean isClosed() {
        return (this.address == 0);
    }

    private void validateState() {
        if (this.isClosed()) throw new IllegalStateException("This view of the MumbleLink data is no longer valid.");
    }

    private static String wcharsToString(ByteBuffer data, int offset, int length) {
        byte[] array = new byte[length * 2];
        int strLength = 0;
        boolean isValueAtLastEvenIndexZero = false;

        for (int i = 0; i < array.length; i++) {
            array[i] = data.get(offset + i);

            if (i % 2 == 0) {
                isValueAtLastEvenIndexZero = array[i] == 0;
            } else if (isValueAtLastEvenIndexZero) {
                strLength = i - 1;
                break;
            }
        }

        return strLength > 0 ? new String(array, 0, strLength, StandardCharsets.UTF_16LE) : "";
    }

    /*
     * struct LinkedMem {                               OFFSET      # ELEMENTS      # BYTES
     *     uint32_t uiVersion;                               0               1            4
     *     uint32_t uiTick;                                  4               1            4
     *     float fAvatarPosition[3];                         8               3           12
     *     float fAvatarFront[3];                           20               3           12
     *     float fAvatarTop[3];                             32               3           12
     *     wchar_t name[256];                               44             256          512
     *     float fCameraPosition[3];                       556               3           12
     *     float fCameraFront[3];                          568               3           12
     *     float fCameraTop[3];                            580               3           12
     *     wchar_t identity[256];                          592             256          512
     *     uint32_t context_len;                          1104               1            4
     *     unsigned char context[256] {                   1108             256          256
     *          unsigned char serverAddress[28];          1108+0            28           28
     *          uint32_t mapId;                           1108+28            1            4
     *          uint32_t mapType;                         1108+32            1            4
     *          uint32_t shardId;                         1108+36            1            4
     *          uint32_t instance;                        1108+40            1            4
     *          uint32_t buildId;                         1108+44            1            4
     *          uint32_t uiState;                         1108+48            1            4
     *          uint16_t compassWidth;                    1108+52            1            2
     *          uint16_t compassHeight;                   1108+54            1            2
     *          float compassRotation;                    1108+56            1            4
     *          float playerX;                            1108+60            1            4
     *          float playerY;                            1108+64            1            4
     *          float mapCenterX;                         1108+68            1            4
     *          float mapCenterY;                         1108+72            1            4
     *          float mapScale;                           1108+76            1            4
     *     }
     *     wchar_t description[2048];                     1364            2048         4096
     * }
     *
     * TOTAL BYTES: 5460
     */
    private static final int OFFSET_uiVersion               = 0,
                             OFFSET_uiTick                  = 4,
                             OFFSET_fAvatarPosition         = 8,
                             OFFSET_fAvatarFront            = 20,
                             OFFSET_fAvatarTop              = 32,
                             OFFSET_name                    = 44,
                             OFFSET_fCameraPosition         = 556,
                             OFFSET_fCameraFront            = 568,
                             OFFSET_fCameraTop              = 580,
                             OFFSET_identity                = 592,
                             OFFSET_context_len             = 1104,
                             OFFSET_context                 = 1108,
                             OFFSET_Context_serverAddress   = OFFSET_context,
                             OFFSET_Context_mapId           = OFFSET_context + 28,
                             OFFSET_Context_mapType         = OFFSET_context + 32,
                             OFFSET_Context_shardId         = OFFSET_context + 36,
                             OFFSET_Context_instance        = OFFSET_context + 40,
                             OFFSET_Context_buildId         = OFFSET_context + 44,
                             OFFSET_Context_uiState         = OFFSET_context + 48,
                             OFFSET_Context_compassWidth    = OFFSET_context + 52,
                             OFFSET_Context_compassHeight   = OFFSET_context + 54,
                             OFFSET_Context_compassRotation = OFFSET_context + 56,
                             OFFSET_Context_playerX         = OFFSET_context + 60,
                             OFFSET_Context_playerY         = OFFSET_context + 64,
                             OFFSET_Context_mapCenterX      = OFFSET_context + 68,
                             OFFSET_Context_mapCenterY      = OFFSET_context + 72,
                             OFFSET_Context_mapScale        = OFFSET_context + 76,
                             OFFSET_description             = 1364;


    /**
     * TODO doc
     *
     * @return
     *
     * @throws IllegalStateException    if this view was {@link #isClosed() invalidated}
     *
     * @since   0.1.0
     */
    public long getUIVersion() {
        this.validateState();
        return Integer.toUnsignedLong(this.data.getInt(OFFSET_uiVersion));
    }

    /**
     * TODO doc
     *
     * @return
     *
     * @throws IllegalStateException    if this view was {@link #isClosed() invalidated}
     *
     * @since   0.1.0
     */
    public long getUITick() {
        this.validateState();
        return Integer.toUnsignedLong(this.data.getInt(OFFSET_uiTick));
    }

    /**
     * Returns the position of the avatar (in meters).
     *
     * <p>Notes:</p>
     *
     * <ul>
     * <li>Guild Wars 2's units correspond to inches.</li>
     * <li>The coordinate system used by MumbleLink is left-handed.</li>
     * <li>Due to limitations of the MumbleLink mechanism, it is possible that the underlying data is modified while
     * reading, thus the returned position may be incorrect. (In practice, this is fairly rare and can just be ignored
     * for the most part. Implement basic interpolation, if necessary.)</li>
     * </ul>
     *
     * @param dest  the array to store the data in
     *
     * @return  the {@code dest} array
     *
     * @throws IllegalArgumentException if {@code dest.length != 3}
     * @throws IllegalStateException    if this view was {@link #isClosed() invalidated}
     *
     * @since   0.1.0
     */
    public float[] getAvatarPosition(float[] dest) {
        if (dest.length != 3) throw new IllegalArgumentException();

        this.validateState();
        for (int i = 0; i < 3; i++) dest[i] = data.getFloat(OFFSET_fAvatarPosition + i * Float.BYTES);

        return dest;
    }

    /**
     * TODO doc
     *
     * @param dest  the array to store the data in
     *
     * @return  the {@code dest} array
     *
     * @throws IllegalArgumentException if {@code dest.length != 3}
     * @throws IllegalStateException    if this view was {@link #isClosed() invalidated}
     *
     * @since   0.1.0
     */
    public float[] getAvatarFront(float[] dest) {
        if (dest.length != 3) throw new IllegalArgumentException();

        this.validateState();
        for (int i = 0; i < 3; i++) dest[i] = data.getFloat(OFFSET_fAvatarFront + i * Float.BYTES);

        return dest;
    }

    /**
     * TODO doc
     *
     * @param dest  the array to store the data in
     *
     * @return  the {@code dest} array
     *
     * @throws IllegalArgumentException if {@code dest.length != 3}
     * @throws IllegalStateException    if this view was {@link #isClosed() invalidated}
     *
     * @since   0.1.0
     */
    public float[] getAvatarTop(float[] dest) {
        if (dest.length != 3) throw new IllegalArgumentException();

        this.validateState();
        for (int i = 0; i < 3; i++) dest[i] = data.getFloat(OFFSET_fAvatarTop + i * Float.BYTES);

        return dest;
    }

    /**
     * Returns the name of the application which updated the underlying data last.
     *
     * @return  the name of the application which updated the underlying data last
     *
     * @throws IllegalStateException    if this view was {@link #isClosed() invalidated}
     *
     * @apiNote In practice this should always be {@code "Guild Wars 2"} when the game client has focus and is the owner
     *          of the link data. It is common practice to use this value to check which application is currently
     *          writing to the MumbleLink data.
     *
     * @since   0.1.0
     */
    public String getName() {
        this.validateState();
        return wcharsToString(this.data, OFFSET_name, 1024);
    }

    /**
     * Returns the position of the camera (in meters).
     *
     * <p>Notes:</p>
     *
     * <ul>
     * <li>Guild Wars 2's units correspond to inches.</li>
     * <li>The coordinate system used by MumbleLink is left-handed.</li>
     * <li>Due to limitations of the MumbleLink mechanism, it is possible that the underlying data is modified while
     * reading, thus the returned position may be incorrect. (In practice, this is fairly rare and can just be ignored
     * for the most part. Implement basic interpolation, if necessary.)</li>
     * </ul>
     *
     * @param dest  the array to store the data in
     *
     * @return  the {@code dest} array
     *
     * @throws IllegalArgumentException if {@code dest.length != 3}
     * @throws IllegalStateException    if this view was {@link #isClosed() invalidated}
     *
     * @since   0.1.0
     */
    public float[] getCameraPosition(float[] dest) {
        if (dest.length != 3) throw new IllegalArgumentException();

        this.validateState();
        for (int i = 0; i < 3; i++) dest[i] = data.getFloat(OFFSET_fCameraPosition + i * Float.BYTES);

        return dest;
    }

    /**
     * TODO doc
     *
     * @param dest  the array to store the data in
     *
     * @return  the {@code dest} array
     *
     * @throws IllegalArgumentException if {@code dest.length != 3}
     * @throws IllegalStateException    if this view was {@link #isClosed() invalidated}
     *
     * @since   0.1.0
     */
    public float[] getCameraFront(float[] dest) {
        if (dest.length != 3) throw new IllegalArgumentException();

        this.validateState();
        for (int i = 0; i < 3; i++) dest[i] = data.getFloat(OFFSET_fCameraFront + i * Float.BYTES);

        return dest;
    }

    /**
     * TODO doc
     *
     * @param dest  the array to store the data in
     *
     * @return  the {@code dest} array
     *
     * @throws IllegalArgumentException if {@code dest.length != 3}
     * @throws IllegalStateException    if this view was {@link #isClosed() invalidated}
     *
     * @since   0.1.0
     */
    public float[] getCameraTop(float[] dest) {
        if (dest.length != 3) throw new IllegalArgumentException();

        this.validateState();
        for (int i = 0; i < 3; i++) dest[i] = data.getFloat(OFFSET_fCameraTop + i * Float.BYTES);

        return dest;
    }

    /**
     * A JSON-formatted {@code String} with additional information.
     *
     * @return  a JSON-formatted {@code String} with additional information
     *
     * @throws IllegalStateException    if this view was {@link #isClosed() invalidated}
     *
     * @since   0.1.0
     */
    public String getIdentity() {
        this.validateState();
        return wcharsToString(this.data, OFFSET_identity, 1024);
    }

    /**
     * Returns the length of the context (in bytes) that will be used by a MumbleServer to determine whether two users
     * can hear each other positionally.
     *
     * @return  the length of the context (in bytes) that will be used by a MumbleServer to determine whether two users
     *          can hear each other positionally
     *
     * @throws IllegalStateException    if this view was {@link #isClosed() invalidated}
     *
     * @since   0.1.0
     */
    public long getContextLength() {
        this.validateState();
        return Integer.toUnsignedLong(this.data.getInt(OFFSET_context_len));
    }

    /**
     * Returns a {@link Context} object that may be used to access the additional context information.
     *
     * <p>The returned object is strongly tied to this object and is only valid as long as this object is valid.</p>
     *
     * @return  a {@code Context} object that may be used to access the additional context information.
     *
     * @since   0.1.0
     */
    public Context getContext() {
        return this.context;
    }

    /**
     * TODO doc
     *
     * @return
     *
     * @throws IllegalStateException    if this view was {@link #isClosed() invalidated}
     *
     * @since   0.1.0
     */
    public String getDescription() {
        this.validateState();
        return wcharsToString(this.data, OFFSET_description, 8192);
    }

    /**
     * See {@link #getContext()}.
     *
     * @since   0.1.0
     */
    public final class Context {

        /**
         * The number of bytes that are used to store the context in the underlying data.
         *
         * @since   0.1.0
         */
        public static final int BYTES = 256;

        /**
         * Shorthand for {@code copy(0, dest, 0, BYTES)}.
         *
         * <p>See {@link #copy(int, byte[], int, int)}.</p>
         *
         * @param dest  the destination array
         *
         * @throws IllegalStateException        if this view was {@link #isClosed() invalidated}
         * @throws IndexOutOfBoundsException    if any index is violated
         * @throws NullPointerException         if {@code dest} is {@code null}
         *
         * @since   0.1.0
         */
        public void copy(byte[] dest) {
            this.copy(0, dest, 0, BYTES);
        }

        /**
         * Copies the underlying data beginning at the specified offset, to the specified offset of the destination
         * array.
         *
         * <p>If any of the following is true, an {@linkplain IndexOutOfBoundsException} is thrown and the destination
         * is not modified:</p>
         *
         * <ul>
         *     <li>The {@code srcOffset} argument is negative.</li>
         *     <li>The {@code destOffset} argument is negative.</li>
         *     <li>The {@code length} argument is negative.</li>
         *     <li>{@code srcOffset + length} is greater than {@link #BYTES}, the length of the context</li>
         *     <li>{@code destOffset + length} is greater than {@code dest.length}, the length of the destination array.</li>
         * </ul>
         *
         * @param srcOffset     starting position in the context
         * @param dest          the destination array
         * @param destOffset    starting position in the destination data
         * @param length        the number of bytes to be copied
         *
         * @throws IllegalStateException        if this view was {@link #isClosed() invalidated}
         * @throws IndexOutOfBoundsException    if any index is violated
         * @throws NullPointerException         if {@code dest} is {@code null}
         *
         * @since   0.1.0
         */
        public void copy(int srcOffset, byte[] dest, int destOffset, int length) {
            MumbleLink.this.validateState();
            Objects.requireNonNull(dest);

            if (srcOffset < 0) throw new IndexOutOfBoundsException("srcOffset must be non-negative");
            if (destOffset < 0) throw new IndexOutOfBoundsException("destOffset must be non-negative");
            if (srcOffset + length > BYTES) throw new IndexOutOfBoundsException();
            if (destOffset + length > dest.length) throw new IndexOutOfBoundsException();

            for (int i = 0; i < length; i++) {
                dest[destOffset + i] = MumbleLink.this.data.get(srcOffset + i);
            }
        }

        private static final int AF_INET    = 2,
                                 AF_INET6   = 10;

        /**
         * Returns the address of the map server that the game client is currently connected to.
         *
         * @return  the address of the map server that the game client is currently connected to
         *
         * @throws IllegalStateException    if this view was {@link #isClosed() invalidated}
         *
         * @since   0.1.0
         */
        @Nullable
        public InetSocketAddress getServerAddress() {
            MumbleLink.this.validateState();

            int port;
            InetAddress inetAddress;

            switch (Short.toUnsignedInt(MumbleLink.this.data.getShort(OFFSET_Context_serverAddress))) {
                case AF_INET: {
                    /*
                     * struct sockaddr_in {
                     *     sa_family_t    sin_family;   // AF_INET                      <-- ONLY 16bit on Windows for some reason...
                     *     in_port_t      sin_port;     // port in network byte order
                     *     struct in_addr sin_addr;     // internet address
                     * }
                     *
                     * struct in_addr {
                     *     uint32_t       s_addr;       // address in network byte order
                     * }
                     */
                    port = MumbleLink.this.data.get(OFFSET_Context_serverAddress + 2);

                    byte[] addr = new byte[4];
                    for (int i = 0; i < addr.length; i++) addr[i] = MumbleLink.this.data.get(OFFSET_Context_serverAddress + 4 + i);

                    try {
                        inetAddress = InetAddress.getByAddress(addr);
                    } catch (UnknownHostException e) {
                        throw new RuntimeException(e);
                    }
                } break;
                case AF_INET6: {
                    /*
                     * struct sockaddr_in6 {
                     *      sa_family_t     sin6_family;    // AF_INET6                 <-- ONLY 16bit on Windows for some reason...
                     *      in_port_t       sin6_port;      // port number
                     *      uint32_t        sin6_flowinfo;  // IPv6 flow information
                     *      struct in6_addr sin6_addr;      // IPv6 address
                     *      uint32_t        sin6_scope_id;  // Scope ID (new in 2.4)
                     * }
                     *
                     * struct in6_addr {
                     *     unsigned char   s6_addr[16];     // IPv6 address
                     * }
                     */
                    port = MumbleLink.this.data.get(OFFSET_Context_serverAddress + 2);
                    // TODO flow information is currently ignored (but should not be required).

                    byte[] addr = new byte[16];
                    for (int i = 0; i < addr.length; i++) addr[i] = MumbleLink.this.data.get(OFFSET_Context_serverAddress + 8 + i);

                    int scopeId = MumbleLink.this.data.get(OFFSET_Context_serverAddress + 24);

                    try {
                        inetAddress = Inet6Address.getByAddress(null, addr, scopeId);
                    } catch (UnknownHostException e) {
                        throw new RuntimeException(e);
                    }
                } break;
                default: return null;
            }

            return new InetSocketAddress(inetAddress, port);
        }

        /**
         * Returns the ID of the map the player is currently on (as per
         * <a href="https://wiki.guildwars2.com/wiki/API:2/maps">Guild Wars 2 API</a>).
         *
         * @return  the ID of the map the player is currently on
         *
         * @throws IllegalStateException    if this view was {@link #isClosed() invalidated}
         *
         * @since   0.1.0
         */
        public long getMapID() {
            MumbleLink.this.validateState();
            return Integer.toUnsignedLong(MumbleLink.this.data.getInt(OFFSET_Context_mapId));
        }

        /**
         * TODO doc
         *
         * @return
         *
         * @throws IllegalStateException    if this view was {@link #isClosed() invalidated}
         *
         * @since   0.1.0
         */
        public long getMapType() {
            MumbleLink.this.validateState();
            return Integer.toUnsignedLong(MumbleLink.this.data.getInt(OFFSET_Context_mapType));
        }

        /**
         * TODO doc
         *
         * @return
         *
         * @throws IllegalStateException    if this view was {@link #isClosed() invalidated}
         *
         * @since   0.1.0
         */
        public long getShardID() {
            MumbleLink.this.validateState();
            return Integer.toUnsignedLong(MumbleLink.this.data.getInt(OFFSET_Context_shardId));
        }

        /**
         * TODO doc
         *
         * @return
         *
         * @throws IllegalStateException    if this view was {@link #isClosed() invalidated}
         *
         * @since   0.1.0
         */
        public long getInstance() {
            MumbleLink.this.validateState();
            return Integer.toUnsignedLong(MumbleLink.this.data.getInt(OFFSET_Context_instance));
        }

        /**
         * Returns the ID of the game build that is currently running (as per
         * <a href="https://wiki.guildwars2.com/wiki/API:2/build">Guild Wars 2 API</a>).
         *
         * @return  the ID of the game build that is currently running
         *
         * @throws IllegalStateException    if this view was {@link #isClosed() invalidated}
         *
         * @since   0.1.0
         */
        public long getBuildID() {
            MumbleLink.this.validateState();
            return Integer.toUnsignedLong(MumbleLink.this.data.getInt(OFFSET_Context_buildId));
        }

        /**
         * A 32bit bitfield that contains various information about the current state of the game UI.
         *
         * <p>The functionality provided {@link UIState} may be used to interpret the value of the bitfield.</p>
         *
         * @return  a 32bit bitfield that contains various information about the current state of the game UI.
         *
         * @throws IllegalStateException    if this view was {@link #isClosed() invalidated}
         *
         * @since   0.1.0
         */
        public int getUIState() {
            MumbleLink.this.validateState();
            return MumbleLink.this.data.getInt(OFFSET_Context_uiState);
        }

        /**
         * Returns the width of the compass in pixels.
         *
         * @return  the width of the compass in pixels
         *
         * @throws IllegalStateException    if this view was {@link #isClosed() invalidated}
         *
         * @since   0.1.0
         */
        public int getCompassWidth() {
            MumbleLink.this.validateState();
            return Short.toUnsignedInt(MumbleLink.this.data.getShort(OFFSET_Context_compassWidth));
        }

        /**
         * Returns the height of the compass in pixels.
         *
         * @return  the height of the compass in pixels
         *
         * @throws IllegalStateException    if this view was {@link #isClosed() invalidated}
         *
         * @since   0.1.0
         */
        public int getCompassHeight() {
            MumbleLink.this.validateState();
            return Short.toUnsignedInt(MumbleLink.this.data.getShort(OFFSET_Context_compassHeight));
        }

        /**
         * Returns the rotation of the compass in radians.
         *
         * @return  the rotation of the compass in radians
         *
         * @throws IllegalStateException    if this view was {@link #isClosed() invalidated}
         *
         * @since   0.1.0
         */
        public float getCompassRotation() {
            MumbleLink.this.validateState();
            return MumbleLink.this.data.getFloat(OFFSET_Context_compassRotation);
        }

        /**
         * Returns the {@code X}-component of the position of the player in continent coordinates.
         *
         * @return  the {@code X}-component of the position of the player in continent coordinates
         *
         * @throws IllegalStateException    if this view was {@link #isClosed() invalidated}
         *
         * @since   0.1.0
         */
        public float getPlayerX() {
            MumbleLink.this.validateState();
            return MumbleLink.this.data.getFloat(OFFSET_Context_playerX);
        }

        /**
         * Returns the {@code Y}-component of the position of the player in continent coordinates.
         *
         * @return  the {@code Y}-component of the position of the player in continent coordinates
         *
         * @throws IllegalStateException    if this view was {@link #isClosed() invalidated}
         *
         * @since   0.1.0
         */
        public float getPlayerY() {
            MumbleLink.this.validateState();
            return MumbleLink.this.data.getFloat(OFFSET_Context_playerY);
        }

        /**
         * Returns the {@code X}-component of the position at the center of the map.
         *
         * @return  the {@code X}-component of the position at the center of the map
         *
         * @throws IllegalStateException    if this view was {@link #isClosed() invalidated}
         *
         * @since   0.1.0
         */
        public float getMapCenterX() {
            MumbleLink.this.validateState();
            return MumbleLink.this.data.getFloat(OFFSET_Context_mapCenterX);
        }

        /**
         * Returns the {@code Y}-component of the position at the center of the map.
         *
         * @return  the {@code Y}-component of the position at the center of the map
         *
         * @throws IllegalStateException    if this view was {@link #isClosed() invalidated}
         *
         * @since   0.1.0
         */
        public float getMapCenterY() {
            MumbleLink.this.validateState();
            return MumbleLink.this.data.getFloat(OFFSET_Context_mapCenterY);
        }

        /**
         * Returns the scale of the map.
         *
         * @return  the scale of the map
         *
         * @throws IllegalStateException    if this view was {@link #isClosed() invalidated}
         *
         * @since   0.1.0
         */
        public float getMapScale() {
            MumbleLink.this.validateState();
            return MumbleLink.this.data.getFloat(OFFSET_Context_mapScale);
        }

    }

}