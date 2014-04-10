package org.projectreactor.examples.thumbnailer;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.projectreactor.examples.thumbnailer.service.GraphicsMagickThumbnailService;
import org.projectreactor.examples.thumbnailer.service.ImageThumbnailService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import reactor.core.Environment;
import reactor.core.Reactor;
import reactor.core.spec.Reactors;
import reactor.event.Event;
import reactor.net.NetServer;
import reactor.net.config.ServerSocketOptions;
import reactor.net.netty.NettyServerSocketOptions;
import reactor.net.netty.tcp.NettyTcpServer;
import reactor.net.tcp.spec.TcpServerSpec;
import reactor.spring.context.config.EnableReactor;

import java.util.concurrent.CountDownLatch;

/**
 * Simple Spring Boot app to start a Reactor+Netty-based REST API server for thumbnailing uploaded images.
 */
@EnableAutoConfiguration
@Configuration
@ComponentScan
@EnableReactor
public class ImageThumbnailerApp {

	@Bean
	public ImageThumbnailService imageThumbnailService() {
		// Prefer the GraphicsMagick version because it's better
		//  but use the below if the "gm" command isn't available on your system.
		//return new BufferedImageThumbnailService();
		return new GraphicsMagickThumbnailService();
	}

	@Bean
	public Reactor reactor(Environment env) {
		return Reactors.reactor(env, Environment.THREAD_POOL);
	}

	@Bean
	public ServerSocketOptions serverSocketOptions() {
		return new NettyServerSocketOptions()
				.pipelineConfigurer(pipeline -> pipeline.addLast(new HttpServerCodec())
				                                        .addLast(new HttpObjectAggregator(16 * 1024 * 1024)));
	}

	@Bean
	public CountDownLatch closeLatch() {
		return new CountDownLatch(1);
	}

	@Bean
	public NetServer<FullHttpRequest, FullHttpResponse> restApi(Environment env,
	                                                            ServerSocketOptions opts,
	                                                            Reactor reactor) throws InterruptedException {
		NetServer<FullHttpRequest, FullHttpResponse> server = new TcpServerSpec<FullHttpRequest, FullHttpResponse>(
				NettyTcpServer.class)
				.env(env).dispatcher("sync").options(opts)
				.consume(ch -> ch.when(Throwable.class, new HttpErrorHandler(ch))
				                 .consume(req -> {
					                 // event source requests
					                 reactor.sendAndReceive(req.getUri(),
					                                        Event.wrap(req),
					                                        (Event<FullHttpResponse> ev) -> ch.send(ev.getData()));
				                 }))
				.get();

		server.start().await();

		return server;
	}

	public static void main(String... args) throws InterruptedException {
		ApplicationContext ctx = SpringApplication.run(ImageThumbnailerApp.class, args);

		// Reactor's TCP servers are non-blocking so we have to do something to keep from exiting the main thread
		CountDownLatch closeLatch = ctx.getBean(CountDownLatch.class);
		closeLatch.await();

		ctx.getBean(NetServer.class).shutdown().await();
	}

}
