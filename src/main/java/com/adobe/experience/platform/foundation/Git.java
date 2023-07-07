package com.adobe.experience.platform.foundation;

import lombok.Data;

import java.net.URI;
import java.util.Arrays;

@Data
public class Git {
	String id;
	String url;
	Challenge challenge;
	String partitionKey = "program";

	String getBranch() {
		return "main";
	}
	String getOwner() {
		String path = getPath();
		return Arrays.stream(path.split("/")).findFirst().get();
	}

	String getRepository() {
		String path = getPath();
		return Arrays.stream(URI.create(url).getPath().split("/")).reduce((first, second) -> second).get();
	}

	public String live() {
		return String.format("https://%s--%s--%s.hlx.live", getBranch(), getRepository(), getOwner());
	}
	public String page() {
		return String.format("https://%s--%s--%s.hlx.page", getBranch(), getRepository(), getOwner());
	}

	public String fqdn() {
		return URI.create(live()).getHost();
	}

	private String getPath() {
		URI uri = URI.create(url);
		String format = String.format("%s://%s/", uri.getScheme(), uri.getHost());
		return url.substring(format.length(), url.length());
	}

}
