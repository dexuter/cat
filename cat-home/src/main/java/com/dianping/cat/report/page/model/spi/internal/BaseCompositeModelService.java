package com.dianping.cat.report.page.model.spi.internal;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.codehaus.plexus.personality.plexus.lifecycle.phase.Initializable;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.InitializationException;

import com.dianping.cat.Cat;
import com.dianping.cat.message.Event;
import com.dianping.cat.message.Message;
import com.dianping.cat.message.Transaction;
import com.dianping.cat.message.internal.DefaultEvent;
import com.dianping.cat.report.page.model.spi.ModelRequest;
import com.dianping.cat.report.page.model.spi.ModelResponse;
import com.dianping.cat.report.page.model.spi.ModelService;
import com.site.helper.Splitters;
import com.site.lookup.annotation.Inject;

public abstract class BaseCompositeModelService<T> implements ModelService<T>, Initializable {
	@Inject
	private List<ModelService<T>> m_services;

	private ExecutorService m_threadPool;

	private String m_name;

	// introduce another list is due to a bug inside Plexus ComponentList
	private List<ModelService<T>> m_allServices = new ArrayList<ModelService<T>>();

	public BaseCompositeModelService(String name) {
		m_name = name;
	}

	protected abstract BaseRemoteModelService<T> createRemoteService();

	public String getName() {
		return m_name;
	}

	@Override
	public void initialize() throws InitializationException {
		m_threadPool = Executors.newFixedThreadPool(10);
		m_allServices.addAll(m_services);
	}

	@Override
	public ModelResponse<T> invoke(final ModelRequest request) {
		int size = m_allServices.size();
		final List<ModelResponse<T>> responses = new ArrayList<ModelResponse<T>>(size);
		final Semaphore semaphore = new Semaphore(0);
		final Transaction t = Cat.getProducer().newTransaction("ModelService", m_name);
		int count = 0;

		t.setStatus(Message.SUCCESS);
		t.addData("request", request);

		for (final ModelService<T> service : m_allServices) {
			if (!service.isEligable(request)) {
				continue;
			}

			m_threadPool.submit(new Runnable() {
				@Override
				public void run() {
					try {
						responses.add(service.invoke(request));

						t.addData(service.toString());
					} catch (Exception e) {
						logError(t, e);
						t.setStatus(e);
					} finally {
						semaphore.release();
					}
				}
			});
			count++;
		}

		try {
			semaphore.tryAcquire(count, 5000, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			// ignore it
			t.setStatus(e);
		} finally {
			t.complete();
		}

		ModelResponse<T> aggregated = new ModelResponse<T>();
		T report = merge(responses);

		aggregated.setModel(report);
		return aggregated;
	}

	@Override
	public boolean isEligable(ModelRequest request) {
		for (ModelService<T> service : m_allServices) {
			if (service.isEligable(request)) {
				return true;
			}
		}

		return false;
	}

	void logError(Transaction t, Throwable cause) {
		StringWriter writer = new StringWriter(2048);

		cause.printStackTrace(new PrintWriter(writer));

		if (cause instanceof Error) {
			logEvent(t, "Error", cause.getClass().getName(), "ERROR", writer.toString());
		} else if (cause instanceof RuntimeException) {
			logEvent(t, "RuntimeException", cause.getClass().getName(), "ERROR", writer.toString());
		} else {
			logEvent(t, "Exception", cause.getClass().getName(), "ERROR", writer.toString());
		}
	}

	void logEvent(Transaction t, String type, String name, String status, String nameValuePairs) {
		Event event = new DefaultEvent(type, name);

		if (nameValuePairs != null && nameValuePairs.length() > 0) {
			event.addData(nameValuePairs);
		}

		event.setStatus(status);
		event.complete();
		t.addChild(event);
	}

	protected abstract T merge(final List<ModelResponse<T>> responses);

	/**
	 * Inject remote servers to load report model.
	 * <p>
	 * 
	 * For example, servers: 192.168.1.1:2281,192.168.1.2,192.168.1.3
	 * 
	 * @param servers
	 *           server list separated by comma(',')
	 */
	public void setRemoteServers(String servers) {
		List<String> endpoints = Splitters.by(',').noEmptyItem().trim().split(servers);
		String localAddress = null;
		String localHost = null;

		try {
			localAddress = InetAddress.getLocalHost().getHostAddress();
			localHost = InetAddress.getLocalHost().getHostName();
		} catch (UnknownHostException e) {
			// ignore it
		}

		for (String endpoint : endpoints) {
			int pos = endpoint.indexOf(':');
			String host = (pos > 0 ? endpoint.substring(0, pos) : endpoint);
			int port = (pos > 0 ? Integer.parseInt(endpoint.substring(pos) + 1) : 2281);

			if (port == 2281) {
				if ("localhost".equals(host) || host.startsWith("127.0.")) {
					// exclude localhost
					continue;
				} else if (host.equals(localAddress) || host.equals(localHost)) {
					// exclude itself
					continue;
				}
			}

			BaseRemoteModelService<T> remote = createRemoteService();

			remote.setHost(host);
			remote.setPort(port);
			m_allServices.add(remote);
		}
	}

	public void setSerivces(ModelService<T>... services) {
		for (ModelService<T> service : services) {
			m_allServices.add(service);
		}
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(64);

		sb.append(getClass().getSimpleName()).append('[');
		sb.append("name=").append(m_name);
		sb.append(']');

		return sb.toString();
	}
}
