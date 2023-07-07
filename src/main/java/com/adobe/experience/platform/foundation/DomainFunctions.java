package com.adobe.experience.platform.foundation;

import com.google.gson.Gson;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import org.xbill.DNS.*;
import org.xbill.DNS.lookup.LookupSession;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * Azure Functions with HTTP Trigger.
 */
public class DomainFunctions {
	@FunctionName("create-domain")
	public HttpResponseMessage createDomain(
			@HttpTrigger(
					name = "request",
					methods = {HttpMethod.POST},
					authLevel = AuthorizationLevel.FUNCTION,
					route = "program/{programId:int}/domain") final HttpRequestMessage<Optional<String>> request,
			@BindingName("programId") long programId,
			@CosmosDBOutput(name = "save",
					databaseName = "foundation",
					collectionName = "domains",
					connectionStringSetting = "foundation_cosmos_connection",
					createIfNotExists = true)
			OutputBinding<Domain> outputItem,
			final ExecutionContext context) {

		context.getLogger().log(Level.INFO, request.getUri().toString());

		Gson gson = new Gson();

		Domain input = gson.fromJson(request.getBody().orElse("{}"), Domain.class);

		input.setPartitionKey("" + programId);
		input.setId(UUID.randomUUID().toString());
		Challenge challenge = new Challenge();
		challenge.setToken(UUID.randomUUID().toString());
		input.setChallenge(challenge);

		outputItem.setValue(input);

		return request.createResponseBuilder(HttpStatus.CREATED)
				.body(gson.toJson(input))
				.build();
	}

	@FunctionName("verify-domain-challenge")
	public HttpResponseMessage verifyChallenge(
			@HttpTrigger(
					name = "request",
					methods = {HttpMethod.GET},
					authLevel = AuthorizationLevel.FUNCTION,
					route = "program/{programId}/domain/{domainId}/challenge")
			HttpRequestMessage<Optional<String>> request,
			@BindingName("programId") String programId,
			@BindingName("domainId") String domainId,
			@CosmosDBInput(name = "in",
					databaseName = "foundation",
					collectionName = "domains",
					connectionStringSetting = "foundation_cosmos_connection",
					id = "{domainId}",
					partitionKey = "{programId}")
			Optional<Domain> domain,
			@CosmosDBOutput(name = "save",
					databaseName = "foundation",
					collectionName = "domains",
					connectionStringSetting = "foundation_cosmos_connection",
					createIfNotExists = true)
			OutputBinding<Domain> outputItem,
			final ExecutionContext context) {

		context.getLogger().log(Level.INFO, "At " + request.getUri().toString() + " found " + domain);

		Domain optional = domain.orElseGet(Domain::new);

		Challenge challenge = optional.getChallenge();

		if (challenge != null) {
			LookupSession s = LookupSession.defaultBuilder().build();

			try {
				Name txt = Name.fromString(String.format("_aemverification.%s.", optional.getFqdn()));
				s.lookupAsync(txt, Type.TXT)
						.whenComplete(
								(answers, ex) -> {
									if (ex == null) {
										if (answers.getRecords().isEmpty()) {
											System.out.println(txt + " has no TXT");
										} else {
											for (Record rec : answers.getRecords()) {
												TXTRecord txtRecord = ((TXTRecord) rec);
												String data = txtRecord.getStrings().stream().findFirst().get();
												String challengeToken = Arrays.stream(data.split("/")).reduce((first, second) -> second).get();
												challenge.setFulfilled(Objects.equals(challengeToken, challenge.getToken()));
												break;
											}
										}
									} else {
										ex.printStackTrace();
									}
								})
						.toCompletableFuture()
						.get();    // dns logic to verify
			} catch (TextParseException | InterruptedException | ExecutionException e) {
				challenge.setFulfilled(false);
			}
		}

		outputItem.setValue(optional);

		return request.createResponseBuilder(HttpStatus.OK)
				.body(optional.getChallenge())
				.build();
	}

	@FunctionName("get-edge-domains")
	public HttpResponseMessage edgeDomains(@HttpTrigger(
			name = "request",
			methods = {HttpMethod.GET},
			authLevel = AuthorizationLevel.FUNCTION,
			route = "program/{programId}/domain/edges")
									   HttpRequestMessage<Optional<String>> request,
									   @BindingName("programId") String programId,
									   @CosmosDBInput(name = "in",
											   databaseName = "foundation",
											   collectionName = "domains",
											   connectionStringSetting = "foundation_cosmos_connection",
											   sqlQuery = "select * from Domains d where d.type = 'edge'",
											   partitionKey = "{programId}")
									   Domain[] domains,
									   final ExecutionContext context) {

		List<String> fqdns = Arrays.stream(domains)
				.filter(domain -> domain.getChallenge().isFulfilled())
				.map(Domain::getFqdn).collect(Collectors.toList());

		return request.createResponseBuilder(HttpStatus.OK)
				.body(fqdns)
				.build();

	}

	@FunctionName("get-origin-domains")
	public HttpResponseMessage originDomains(@HttpTrigger(
			name = "request",
			methods = {HttpMethod.GET},
			authLevel = AuthorizationLevel.FUNCTION,
			route = "program/{programId}/domain/origins")
									   HttpRequestMessage<Optional<String>> request,
									   @BindingName("programId") String programId,
									   @CosmosDBInput(name = "in",
											   databaseName = "foundation",
											   collectionName = "repositories",
											   connectionStringSetting = "foundation_cosmos_connection",
											   sqlQuery = "select * from Repositories r",
											   partitionKey = "{programId}")
									   Git[] repositories,
									   final ExecutionContext context) {

		List<String> fqdns = Arrays.stream(repositories)
				.filter(domain -> domain.getChallenge().isFulfilled())
				.map(Git::live).collect(Collectors.toList());

		return request.createResponseBuilder(HttpStatus.OK)
				.body(fqdns)
				.build();

	}

}
