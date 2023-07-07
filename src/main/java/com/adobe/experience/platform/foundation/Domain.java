package com.adobe.experience.platform.foundation;

import lombok.Data;

@Data
public class Domain {
	String id;
	String fqdn;
	String type;
	Challenge challenge;
	String partitionKey = "program";

}
