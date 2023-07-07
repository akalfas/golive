package com.adobe.experience.platform.foundation;

import com.google.gson.Gson;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;

import java.io.*;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * Azure Functions with HTTP Trigger.
 */
public class GitFunctions {
	@FunctionName("create-github-link")
	public HttpResponseMessage createGit(
			@HttpTrigger(
					name = "request",
					methods = {HttpMethod.POST},
					authLevel = AuthorizationLevel.FUNCTION,
					route = "program/{programId:int}/link/github") final HttpRequestMessage<Optional<String>> request,
			@BindingName("programId") long programId,
			@CosmosDBOutput(name = "save",
					databaseName = "foundation",
					collectionName = "repositories",
					connectionStringSetting = "foundation_cosmos_connection",
					createIfNotExists = true)
			OutputBinding<Git> outputItem,
			final ExecutionContext context) {

		context.getLogger().log(Level.INFO, request.getUri().toString());

		Gson gson = new Gson();

		Git input = gson.fromJson(request.getBody().orElse("{}"), Git.class);

		String url = input.getUrl();

		String host = URI.create(url).getHost();

		if (!Objects.equals(host, "github.com")) {
			return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
					.body("github.com supported only")
					.build();
		}

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

	@FunctionName("verify-github-challenge")
	public HttpResponseMessage verifyChallenge(
			@HttpTrigger(
					name = "request",
					methods = {HttpMethod.GET},
					authLevel = AuthorizationLevel.FUNCTION,
					route = "program/{programId}/repository/{repositoryId}/challenge")
			HttpRequestMessage<Optional<String>> request,
			@BindingName("programId") String programId,
			@BindingName("repositoryId") String repositoryId,
			@CosmosDBInput(name = "in",
					databaseName = "foundation",
					collectionName = "repositories",
					connectionStringSetting = "foundation_cosmos_connection",
					id = "{repositoryId}",
					partitionKey = "{programId}")
			Optional<Git> git,
			@CosmosDBOutput(name = "save",
					databaseName = "foundation",
					collectionName = "repositories",
					connectionStringSetting = "foundation_cosmos_connection",
					createIfNotExists = true)
			OutputBinding<Git> outputItem,
			final ExecutionContext context) {

		context.getLogger().log(Level.INFO, "At " + request.getUri().toString() + " found " + git);

		Git optional = git.orElseGet(Git::new);

		Challenge challenge = optional.getChallenge();

		if (challenge != null) {

			HttpClient client = HttpClient.newBuilder()
					.build();

			URI challengeUri = URI.create(String.format("%s/.well-known/adobe/cloudmanager-challenge.txt", optional.page()));

			HttpRequest challengeRequest = HttpRequest.newBuilder()
					.uri(challengeUri)
					.header("Accept-Encoding", "gzip")
					.build();


			client.sendAsync(challengeRequest, HttpResponse.BodyHandlers.ofInputStream())
					.thenApply(HttpResponse::body)
					.thenAccept(zip -> {
						try (BufferedReader buf = new BufferedReader(new InputStreamReader(new GZIPInputStream(zip)))) {
							String token = buf.lines().collect(Collectors.joining());
							optional.getChallenge().setFulfilled(Objects.equals(token, optional.getChallenge().getToken()));
						} catch (IOException iox) {
							context.getLogger().log(Level.SEVERE, iox.getMessage());
							optional.getChallenge().setFulfilled(false);
						}

					})
					.join();
		}

		outputItem.setValue(optional);

		return request.createResponseBuilder(HttpStatus.OK)
				.body(optional.getChallenge())
				.build();
	}
}
