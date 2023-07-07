package com.adobe.experience.platform.foundation;

import com.google.gson.Gson;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class CDNFunctions {

	@FunctionName("create-cdn")
	public HttpResponseMessage createCDN(
			@HttpTrigger(
					name = "request",
					methods = {HttpMethod.POST},
					authLevel = AuthorizationLevel.FUNCTION,
					route = "program/{programId:int}/cdn") final HttpRequestMessage<Optional<String>> request,
			@BindingName("programId") long programId,
			@CosmosDBInput(name = "edges",
					databaseName = "foundation",
					collectionName = "domains",
					connectionStringSetting = "foundation_cosmos_connection",
					sqlQuery = "select * from Domains d where d.type = 'edge'",
					partitionKey = "{programId}")
			Domain[] domains,
			@CosmosDBInput(name = "repositories",
					databaseName = "foundation",
					collectionName = "repositories",
					connectionStringSetting = "foundation_cosmos_connection",
					sqlQuery = "select * from Repositories r",
					partitionKey = "{programId}")
			Git[] repositories,
			@CosmosDBOutput(name = "save",
					databaseName = "foundation",
					collectionName = "cdn",
					connectionStringSetting = "foundation_cosmos_connection",
					createIfNotExists = true)
			OutputBinding<CDN> outputItem,
			final ExecutionContext context) {

		context.getLogger().log(Level.INFO, request.getUri().toString());

		Gson gson = new Gson();

		CDN input = gson.fromJson(request.getBody().orElse("{}"), CDN.class);
		input.setPartitionKey("" + programId);
		input.setId(UUID.randomUUID().toString());
		input.getEdge().setPartitionKey(null);
		input.getOrigin().setPartitionKey(null);

		// basic validation

		boolean validEdge = Arrays.stream(domains)
				.filter(domain -> domain.getChallenge().isFulfilled())
				.map(Domain::getFqdn)
				.collect(Collectors.toList()).contains(input.getEdge().getFqdn());

		boolean validOrigin = Arrays.stream(repositories)
				.filter(git -> git.getChallenge().isFulfilled())
				.map(Git::fqdn)
				.collect(Collectors.toList()).contains(input.getOrigin().getFqdn());

		if (!validOrigin || !validEdge) {
			return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
					.body(String.format("Invalid edge=%s or origin=%s - see program/{programId}/domain/edges and program/{programId}/domain/origins", !validEdge, !validOrigin))
					.build();
		}

		outputItem.setValue(input);

		return request.createResponseBuilder(HttpStatus.CREATED)
				.body(gson.toJson(input))
				.build();
	}
	@FunctionName("cdn-changes")
	public void cosmosDbProcessor(
			@CosmosDBTrigger(name = "changes",
					databaseName = "foundation",
					collectionName = "cdn",
					leaseCollectionName = "leases",
					createLeaseCollectionIfNotExists = true,
					connectionStringSetting = "foundation_cosmos_connection") CDN[] items,
			@CosmosDBOutput(name = "save",
					databaseName = "foundation",
					collectionName = "cdn",
					connectionStringSetting = "foundation_cosmos_connection",
					createIfNotExists = true)
			OutputBinding<List<CDN>> outputItem,
			final ExecutionContext context ) {

		List<CDN> output = Arrays.stream(items)
				.filter(cdn -> !cdn.isRealised())
				.peek(cdn -> cdn.setRealised(true)).collect(Collectors.toList());

		outputItem.setValue(output);

		context.getLogger().info(output.size() + " item(s) is/are changed.");
	}
}
