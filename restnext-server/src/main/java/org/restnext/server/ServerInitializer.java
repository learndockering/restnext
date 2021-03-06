/*
 * Copyright (C) 2016 Thiago Gutenberg Carvalho da Costa
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.restnext.server;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.net.ssl.SSLException;

import org.restnext.core.http.MediaType;
import org.restnext.core.http.Request;
import org.restnext.core.http.Response;
import org.restnext.route.Route;
import org.restnext.route.RouteScanner;
import org.restnext.security.Security;
import org.restnext.security.SecurityScanner;

/**
 * Created by thiago on 04/08/16.
 */
public final class ServerInitializer extends ChannelInitializer<SocketChannel> {

  private final Duration timeout;
  private final SslContext sslCtx;
  private final int maxContentLength;
  private final Compressor compressor;
  private final InetSocketAddress bindAddress;
  private final EventExecutorGroup group;

  private ServerInitializer(final Builder builder) {
    this.sslCtx = builder.sslContext;
    this.maxContentLength = builder.maxContentLength;
    this.bindAddress = builder.bindAddress;
    this.timeout = builder.timeout;
    this.group = builder.eventExecutorGroup;
    this.compressor = builder.compressor;
  }

  @Override
  protected void initChannel(SocketChannel ch) throws Exception {
    ChannelPipeline pipeline = ch.pipeline();
    if (isSslConfigured()) {
      pipeline.addLast("ssl", sslCtx.newHandler(ch.alloc()));
    }
    pipeline.addLast("http", new HttpServerCodec());
    pipeline.addLast("aggregator", new HttpObjectAggregator(maxContentLength));
    if (compressor != null) {
      pipeline.addLast("compressor", new CustomHttpContentCompressor(
          compressor.level,
          compressor.contentLength,
          compressor.types
      ));
    }
    pipeline.addLast("streamer", new ChunkedWriteHandler());
    pipeline.addLast("timeout", new ReadTimeoutHandler(timeout.getSeconds(), TimeUnit.SECONDS));
    // Tell the pipeline to run MyBusinessLogicHandler's event handler methods in a different
    // thread than an I/O thread so that the I/O thread is not blocked by a time-consuming task.
    // If your business logic is fully asynchronous or finished very quickly, you don't need to
    // specify a group.
    if (group != null) {
      pipeline.addLast(group, "handler", ServerHandler.INSTANCE);
    } else {
      pipeline.addLast("handler", ServerHandler.INSTANCE);
    }
  }

  public static Builder builder() {
    return new ServerInitializer.Builder();
  }

  public static Builder route(String uri, Function<Request, Response> provider) {
    return builder().route(uri, provider);
  }

  public static Builder route(Route.Mapping routeMapping) {
    return routes(routeMapping);
  }

  public static Builder routes(Route.Mapping... routesMapping) {
    return builder().routes(routesMapping);
  }

  public InetSocketAddress getBindAddress() {
    return bindAddress;
  }

  public boolean isSslConfigured() {
    return sslCtx != null;
  }

  private static final class Compressor {

    static final int DEFAULT_COMPRESSION_LEVEL = 6;
    static final int DEFAULT_MINIMUM_CONTENT_LENGTH = 10 * 1024; // 10kb
    static final MediaType[] DEFAULT_COMPRESSIBLE_TYPES = new MediaType[] {
        MediaType.parse("text/css"),
        MediaType.parse("text/javascript"),
        MediaType.parse("*/html"),
        MediaType.parse("text/comma-separated-values"),
        MediaType.parse("*/xml")
    };

    private final int level;
    private final int contentLength;
    private final MediaType[] types;

    /**
     * Creates a new instance with provided parameters.
     *
     * @param level
     *        {@code 1} yields the fastest compression and {@code 9} yields the
     *        best compression.  {@code 0} means no compression.  The default
     *        compression level is {@code 6}.
     * @param contentLength minimum content length for compression
     * @param types compressible media types
     */
    public Compressor(int level, int contentLength, MediaType... types) {
      this.level = level;
      this.contentLength = contentLength;
      this.types = types != null && types.length > 0 ? types : DEFAULT_COMPRESSIBLE_TYPES;
    }
  }

  public static final class Builder {

    static final ServerCertificate DEFAULT_SERVER_CERTIFICATE =
        new ServerSelfSignedCertificate();

    private SslContext sslContext;
    private Compressor compressor;
    private InetSocketAddress bindAddress;
    private EventExecutorGroup eventExecutorGroup;

    // default
    private int maxContentLength = 64 * 1024;
    private Duration timeout = Duration.ofHours(1);

    public Builder bindAddress(InetSocketAddress bindAddress) {
      this.bindAddress = bindAddress;
      return this;
    }

    /**
     * Enable compression with default compression level, default compression content length and
     * default compressible media types.
     *
     * @return server initializer builder
     */
    public Builder enableCompression() {
      return enableCompression(
          Compressor.DEFAULT_COMPRESSION_LEVEL,
          Compressor.DEFAULT_MINIMUM_CONTENT_LENGTH,
          Compressor.DEFAULT_COMPRESSIBLE_TYPES
      );
    }

    /**
     * Enable compression.
     *
     * @param compressionLevel
     *        {@code 1} yields the fastest compression and {@code 9} yields the
     *        best compression.  {@code 0} means no compression.  The default
     *        compression level is {@code 6}.
     * @param compressionContentLength minimum content length for compression
     * @param compressibleTypes compressible media types
     * @return server initializer builder
     */
    public Builder enableCompression(int compressionLevel,
                                     int compressionContentLength,
                                     MediaType... compressibleTypes) {
      this.compressor = new Compressor(
          compressionLevel,
          compressionContentLength,
          compressibleTypes);
      return this;
    }

    /**
     * Timeout duration, default duration is used {@code Duration.ofSeconds(30)}.
     *
     * @param timeout duration timeout
     * @return server initializer builder
     */
    public Builder timeout(Duration timeout) {
      if (timeout != null) {
        this.timeout = timeout;
      }
      return this;
    }

    public Builder maxContentLength(int maxContentLength) {
      this.maxContentLength = maxContentLength;
      return this;
    }

    public Builder executorGroupThreadPoll(int threads) {
      this.eventExecutorGroup = new DefaultEventExecutorGroup(threads);
      return this;
    }

    public Builder ssl() {
      return ssl(DEFAULT_SERVER_CERTIFICATE);
    }

    public Builder ssl(ServerCertificate certificate) {
      return ssl(createSslContext(certificate));
    }

    public Builder ssl(SslContext sslContext) {
      this.sslContext = sslContext;
      return this;
    }

    private SslContext createSslContext(ServerCertificate serverCertificate) {
      try {
        return SslContextBuilder.forServer(
            serverCertificate.getCertificate().toFile(),
            serverCertificate.getPrivateKey().toFile()
        ).build();
      } catch (SSLException ignore) {
        return null;
      }
    }

    public Builder secure(String uri, Function<Request, Boolean> provider) {
      return secures(Security.Mapping.uri(uri, provider).build());
    }

    public final Builder secures(Security.Mapping... securityMapping) {
      Arrays.asList(securityMapping).forEach(Security.INSTANCE::register);
      return this;
    }

    public Builder enableSecurityRoutesScan() {
      return enableSecurityRoutesScan(SecurityScanner.DEFAULT_SECURITY_DIR);
    }

    /**
     * Enable the security route scan approach.
     *
     * @param securityDirectory the security directory to scan
     * @return server initializer builder
     */
    public Builder enableSecurityRoutesScan(Path securityDirectory) {
      SecurityScanner securityScanner = new SecurityScanner(Security.INSTANCE, securityDirectory);
      securityScanner.scan();
      return this;
    }

    public Builder enableRoutesScan() {
      return enableRoutesScan(RouteScanner.DEFAULT_ROUTE_DIR);
    }

    /**
     * Enable the route scan approach.
     *
     * @param routeDirectory the route directory to scan
     * @return server initializer builder
     */
    public Builder enableRoutesScan(Path routeDirectory) {
      RouteScanner routeScanner = new RouteScanner(Route.INSTANCE, routeDirectory);
      routeScanner.scan();
      return this;
    }

    /**
     * Shortcut to start the server.
     *
     * @return the started server
     */
    public Server start() {
      Server server = new Server(build());
      server.start();
      return server;
    }

    /**
     * Build the server initializer.
     *
     * @return the server initializer
     */
    public ServerInitializer build() {

      // before build the server initializer...

      // register default health check route.
      route("/ping", request -> Response.ok("pong").build());

      // register default port.
      if (this.bindAddress == null) {
        if (this.sslContext == null) {
          this.bindAddress = new InetSocketAddress(8080);
        } else {
          this.bindAddress = new InetSocketAddress(8443);
        }
      }
      return new ServerInitializer(this);
    }

    public Builder route(String uri, Function<Request, Response> provider) {
      return route(Route.Mapping.uri(uri, provider).build());
    }

    public Builder route(Route.Mapping routeMapping) {
      return routes(routeMapping);
    }

    public final Builder routes(Route.Mapping... routeMapping) {
      Arrays.asList(routeMapping).forEach(Route.INSTANCE::register);
      return this;
    }

    private static class ServerSelfSignedCertificate implements ServerCertificate {

      static final String DEFAULT_FQND = "restnext.org";

      private SelfSignedCertificate certificate;

      public ServerSelfSignedCertificate() {
        this(DEFAULT_FQND);
      }

      /**
       * Creates a new instance.
       *
       * @param fqdn a fully qualified domain name
       */
      public ServerSelfSignedCertificate(String fqdn) {
        try {
          certificate = new SelfSignedCertificate(fqdn);
        } catch (CertificateException ignore) {
          // nop
        }
      }

      @Override
      public Path getCertificate() {
        return certificate.certificate().toPath();
      }

      @Override
      public Path getPrivateKey() {
        return certificate.privateKey().toPath();
      }
    }

  }
}
