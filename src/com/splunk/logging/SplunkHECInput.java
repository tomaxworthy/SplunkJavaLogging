package com.splunk.logging;

import java.net.URI;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Future;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;

import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.ssl.TrustStrategy;
import org.apache.http.util.EntityUtils;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;

import org.apache.http.nio.conn.NoopIOSessionStrategy;
import org.apache.http.nio.conn.SchemeIOSessionStrategy;
import org.apache.http.nio.conn.ssl.SSLIOSessionStrategy;
import org.apache.http.nio.reactor.ConnectingIOReactor;

import com.google.gson.*;

/**
 * Common HEC logic shared by all appenders/handlers
 * 
 * @author Damien Dallimore damien@baboonbones.com
 * 
 */

public class SplunkHECInput extends SplunkInput {

	// connection props
	private HECTransportConfig config;

	// batch buffer
	private List<String> batchBuffer;
	private long currentBatchSizeBytes = 0;
	private long lastEventReceivedTime;

	private CloseableHttpAsyncClient httpClient;
	private URI uri;

	private static final HostnameVerifier HOSTNAME_VERIFIER = new HostnameVerifier() {
		public boolean verify(String s, SSLSession sslSession) {
			return true;
		}
	};

	public SplunkHECInput(HECTransportConfig config, String activationKey) throws Exception {

		activationKeyCheck(activationKey, config.getEnabled());

		if (activated) {
			this.config = config;

			this.batchBuffer = Collections.synchronizedList(new LinkedList<String>());
			this.lastEventReceivedTime = System.currentTimeMillis();

			Registry<SchemeIOSessionStrategy> sslSessionStrategy = RegistryBuilder.<SchemeIOSessionStrategy> create()
					.register("http", NoopIOSessionStrategy.INSTANCE)
					.register("https", new SSLIOSessionStrategy(getSSLContext(), HOSTNAME_VERIFIER)).build();

			ConnectingIOReactor ioReactor = new DefaultConnectingIOReactor();
			PoolingNHttpClientConnectionManager cm = new PoolingNHttpClientConnectionManager(ioReactor,
					sslSessionStrategy);
			cm.setMaxTotal(config.getPoolsize());

			HttpHost splunk = new HttpHost(config.getHost(), config.getPort());
			cm.setMaxPerRoute(new HttpRoute(splunk), config.getPoolsize());

			httpClient = HttpAsyncClients.custom().setConnectionManager(cm).build();

			uri = new URIBuilder().setScheme(config.isHttps() ? "https" : "http").setHost(config.getHost())
					.setPort(config.getPort()).setPath("/services/collector").build();

			openStream();

			if (config.isBatchMode()) {
				new BatchBufferActivityCheckerThread().start();
			}
		}
	}

	class BatchBufferActivityCheckerThread extends Thread {

		BatchBufferActivityCheckerThread() {

		}

		public void run() {

			while (true) {
				String currentMessage = "";
				try {
					long currentTime = System.currentTimeMillis();
					if ((currentTime - lastEventReceivedTime) >= config.getMaxInactiveTimeBeforeBatchFlush()) {
						if (batchBuffer.size() > 0) {
							synchronized (batchBuffer) {
								currentMessage = rollOutBatchBuffer();
								batchBuffer.clear();
								currentBatchSizeBytes = 0;
							}
							hecPost(currentMessage);
						}
					}

					Thread.sleep(1000);
				} catch (Exception e) {
					// something went wrong , put message on the queue for retry
					if (config.getErrorsToConsole()){
						System.out.println("Failed to forward log via Splunk HEC:" + e.toString());
					}
					enqueue(currentMessage);
					try {
						closeStream();
					} catch (Exception e1) {
					}

					try {
						openStream();
					} catch (Exception e2) {
					}
				}
			}
		}
	}

	private SSLContext getSSLContext() {
		TrustStrategy acceptingTrustStrategy = new TrustStrategy() {
			public boolean isTrusted(X509Certificate[] certificate, String authType) {
				return true;
			}
		};
		SSLContext sslContext = null;
		try {
			sslContext = SSLContexts.custom().loadTrustMaterial(null, acceptingTrustStrategy).build();
		} catch (Exception e) {
			// Handle error
		}
		return sslContext;

	}

	/**
	 * open the stream
	 * 
	 */
	private void openStream() throws Exception {

		httpClient.start();

	}

	/**
	 * close the stream
	 */
	public void closeStream() {
		try {

			httpClient.close();
		} catch (Exception e) {
		}
	}
	
	private boolean messageConvertedToJson(String message){
		String trimmedMessage = message.trim();
		return (trimmedMessage.startsWith("{") && trimmedMessage.endsWith("}"));
	}
	
	/**
	 * send an event via stream
	 * 
	 * @param message
	 */
	public void streamEvent(String message) {

		if (activated) {
			String currentMessage = "";
			try {
				if (messageConvertedToJson(message)){
					currentMessage = message;
				}else
				{
					HECTransportMessage messageObject = new HECTransportMessage(message, config.getIndex(),config.getSource(),config.getSourcetype());
					//splunk is not expecting escaped html chars
					Gson g = new GsonBuilder().disableHtmlEscaping().create();
					currentMessage = g.toJson(messageObject);
				}
				
				if (config.isBatchMode()) {
					lastEventReceivedTime = System.currentTimeMillis();
					currentBatchSizeBytes += currentMessage.length();
					batchBuffer.add(currentMessage);
					if (flushBuffer()) {
						synchronized (batchBuffer) {
							currentMessage = rollOutBatchBuffer();
							batchBuffer.clear();
							currentBatchSizeBytes = 0;
						}
						hecPost(currentMessage);
					}
				} else {
					hecPost(currentMessage);
				}

				// flush the queue
				while (queueContainsEvents()) {
					String messageOffQueue = dequeue();
					currentMessage = messageOffQueue;
					hecPost(currentMessage);
				}

			} catch (Exception e) {
				// something went wrong , put message on the queue for retry
				if (config.getErrorsToConsole()){
					System.out.println(e.getMessage());
				}
				
				enqueue(currentMessage);
				try {
					closeStream();
				} catch (Exception e1) {
				}

				try {
					openStream();
				} catch (Exception e2) {
				}
			}
		}
	}

	private boolean flushBuffer() {

		return (currentBatchSizeBytes >= config.getMaxBatchSizeBytes())
				|| (batchBuffer.size() >= config.getMaxBatchSizeEvents());

	}

	private String rollOutBatchBuffer() {

		StringBuffer sb = new StringBuffer();

		for (String event : batchBuffer) {
			sb.append(event);
		}

		return sb.toString();
	}

	private void hecPost(String currentMessage) throws Exception {

		HttpPost post = new HttpPost(uri);
		post.addHeader("Authorization", "Splunk " + config.getToken());

		StringEntity requestEntity = new StringEntity(currentMessage, ContentType.create("application/json", "UTF-8"));

		post.setEntity(requestEntity);
		Future<HttpResponse> future = httpClient.execute(post, null);
		HttpResponse response = future.get();
		
		if (config.getErrorsToConsole() && response.getStatusLine().getStatusCode() != 200){
			System.out.println(response.getStatusLine());
		 	System.out.println(EntityUtils.toString(response.getEntity()));
		}
	}
}
