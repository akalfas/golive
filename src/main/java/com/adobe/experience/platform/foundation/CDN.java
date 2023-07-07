package com.adobe.experience.platform.foundation;

import lombok.Data;

@Data
public class CDN {
	String id;
	Domain origin;
	Domain edge;
	boolean realised;
	String partitionKey = "program";
}
