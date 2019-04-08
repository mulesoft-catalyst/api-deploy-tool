package com.mulesoft.java;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.mule.consulting.cps.encryption.CpsEncryptor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.databind.type.MapType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;

public class ApiDeployTool {
	public static String HTTPS_ANYPOINT_MULESOFT_COM = "https://anypoint.mulesoft.com";

	public static void main(String[] args) {

		System.err.println("ApiDeployTool version 1.0.1\n");
		try {
			if (args.length <= 0) {
				printHelp();
			} else if (args[0].equals("registerApi")) {
				String json = registerApi((args.length > 1) ? args[1] : "userName",
						(args.length > 2) ? args[2] : "userPass", (args.length > 3) ? args[3] : "orgName",
						(args.length > 4) ? args[4] : "apiName", (args.length > 5) ? args[5] : "apiVersion",
						(args.length > 6) ? args[6] : "DEV", (args.length > 7) ? args[7] : "base",
						(args.length > 8) ? args[8] : "client-credentials-policy",
						(args.length > 9) ? args[9] : "empty-client-access-list");
				System.out.println(json);
			} else {
				printHelp();
			}
		} catch (Exception e) {
			e.printStackTrace(System.err);
			System.exit(500);
		}
	}

	private static void printHelp() {
		System.out.println("\nUsage: java -jar ApiDeployTool {operation} [parameters]\n");
		System.out.println("  operations:");
		System.out.println("    registerApi   -Read the Api definition and publish it to Anypoint Platform");
		System.out.println("      Parameters:");
		System.out.println("          userName      -Anypoint user name");
		System.out.println("          userPassword  -Anypoint user's password");
		System.out.println("          orgName       -Anypoint business org name (no hierarchy)");
		System.out.println("          apiName       -api name");
		System.out.println("          apiVersion    -api version");
		System.out.println("          env           -environment name");
		System.out.println("          keyId         -encryption keyId");
		System.out.println("          policies      -file containing policy definitions (json array)");
		System.out.println("          applications  -file containing client application namess to register for access (json array)");
		System.out.println("\n");
	}

	@SuppressWarnings("unchecked")
	private static String registerApi(String userName, String userPass, String businessGroupName, String apiName,
			String apiVersion, String environmentName, String keyId, String policies, String clients) throws Exception {

		LinkedHashMap<String, Object> returnPayload = new LinkedHashMap<String, Object>();
		LinkedHashMap<String, String> returnPayloadProperties = new LinkedHashMap<String, String>();
		ObjectMapper mapperw = new ObjectMapper();

		Client client = null;
		client = ClientBuilder.newClient();
		client.register(JacksonJsonProvider.class).register(MultiPartFeature.class);

		returnPayload.put("projectName", "auto-api-registation");
		returnPayload.put("branchName", apiName);
		returnPayload.put("instanceId", apiVersion);
		returnPayload.put("envName", environmentName);
		returnPayload.put("keyId", keyId);

		// registration steps

		/*
		 * Authenticate with Anypoint Platform
		 */
		String apToken = getAPToken(client, userName, userPass);
		String authorizationHdr = "Bearer " + apToken;

		/*
		 * Get the login user information, organizationId and business group id
		 */
		LinkedHashMap<String, Object> myInformation = getMyInformation(client, authorizationHdr);
		String myOrganizationId = (String) ((LinkedHashMap<String, Object>) myInformation.get("user"))
				.get("organizationId");
		String myOrganizationName = (String) ((LinkedHashMap<String, Object>) ((LinkedHashMap<String, Object>) myInformation
				.get("user")).get("organization")).get("name");

		ArrayList<LinkedHashMap<String, Object>> memberOfOrganizations = (ArrayList<LinkedHashMap<String, Object>>) ((LinkedHashMap<String, Object>) myInformation
				.get("user")).get("memberOfOrganizations");
		LinkedHashMap<String, Object> businessGroupInformation = getBusinessGroupInformation(memberOfOrganizations,
				businessGroupName);
		String businessGroupId = (String) businessGroupInformation.get("id");

		/*
		 * Get the environment id
		 */
		LinkedHashMap<String, Object> environment = getEnvironmentInformation(client, authorizationHdr, businessGroupId,
				environmentName);
		String environmentId = (String) environment.get("id");

		/*
		 * Create default CPS credential if it doesn't already exist
		 */
		String cps_client_name = null;
		String cps_client_id = null;
		String cps_client_secret = null;
		StringBuilder cpsName = new StringBuilder();
		cpsName.append("configuration-property-service").append("_").append(businessGroupName).append("_")
				.append(environmentName);
		createApplication(client, authorizationHdr, myOrganizationId, cpsName.toString(),
				"Use for interacting with configuration property service");
		ArrayList<LinkedHashMap<String, Object>> applications = getApplicationList(client, authorizationHdr, myOrganizationId);
		LinkedHashMap<String, Object> applicationInfo = null;
		for (LinkedHashMap<String, Object> e:applications) {
			if (e.get("name").equals(cpsName.toString())) {
				applicationInfo = getApplicationInformation(client, authorizationHdr, myOrganizationId, (int) e.get("id"));
				cps_client_name = (String) applicationInfo.get("name");
				cps_client_id = (String) applicationInfo.get("clientId");
				cps_client_secret = (String) applicationInfo.get("clientSecret");
				break;
			}
		}

		/*
		 * Create auto-registration credential if it doesn't already exist
		 */
		String auto_reg_client_name = null;
		String auto_reg_client_id = null;
		String auto_reg_client_secret = null;
		StringBuilder autoRegistrationName = new StringBuilder();
		autoRegistrationName.append("auto-api-registration").append("_").append(businessGroupName).append("_")
				.append(environmentName);
		createApplication(client, authorizationHdr, myOrganizationId, autoRegistrationName.toString(),
				"Use for interacting with Auto Registration");
		applications = getApplicationList(client, authorizationHdr, myOrganizationId);
		applicationInfo = null;
		for (LinkedHashMap<String, Object> e:applications) {
			if (e.get("name").equals(autoRegistrationName.toString())) {
				applicationInfo = getApplicationInformation(client, authorizationHdr, myOrganizationId, (int) e.get("id"));
				auto_reg_client_name = (String) applicationInfo.get("name");
				auto_reg_client_id = (String) applicationInfo.get("clientId");
				auto_reg_client_secret = (String) applicationInfo.get("clientSecret");
				break;
			}
		}

		/*
		 * Create the API in Exchange
		 */
		ArrayList<LinkedHashMap<String, Object>> apiAssets = null;
		apiAssets = getExchangeAssets(client, authorizationHdr, businessGroupId, apiName);

		LinkedHashMap<String, Object> apiAsset = null;
		apiAsset = findApiAsset(apiAssets, myOrganizationName, businessGroupName, apiName, apiVersion);

		if (apiAsset == null) {
			publishAPItoExchange(client, authorizationHdr, apiName, apiVersion, myOrganizationName, myOrganizationId,
					businessGroupName, businessGroupId);
			apiAssets = getExchangeAssets(client, authorizationHdr, businessGroupId, apiName);
			apiAsset = findApiAsset(apiAssets, myOrganizationName, businessGroupName, apiName, apiVersion);
		}
		String exchangeAssetId = (String) apiAsset.get("assetId");
		String exchangeAssetVersion = (String) apiAsset.get("version");
		String exchangeAssetName = (String) apiAsset.get("name");

		/*
		 * Create an API Instance in API Manager
		 */
		LinkedHashMap<String, Object> apiManagerAsset = null;
		apiManagerAsset = getApiManagerAsset(client, authorizationHdr, businessGroupId, environmentId, exchangeAssetId,
				exchangeAssetVersion);
		if (apiManagerAsset == null) {
			registerAPIInstance(client, authorizationHdr, businessGroupId, environmentId, exchangeAssetId,
					exchangeAssetVersion);
			apiManagerAsset = getApiManagerAsset(client, authorizationHdr, businessGroupId, environmentId, exchangeAssetId,
					exchangeAssetVersion);
		}
		String apiManagerAssetId = apiManagerAsset.get("id").toString();
		String autoDiscoveryApiName = (String) apiManagerAsset.get("autodiscoveryApiName");
		String autoDiscoveryApiVersion = null;
		String autoDiscoveryApiId = null;
		for (LinkedHashMap<String, Object> e:(ArrayList<LinkedHashMap<String, Object>>) apiManagerAsset.get("apis")) {
			if (e.get("instanceLabel").equals("auto-api-registation-" + exchangeAssetId)) {
				autoDiscoveryApiVersion = (String) e.get("autodiscoveryInstanceName");
				autoDiscoveryApiId = e.get("id").toString();
				break;
			}
		}

		/*
		 * Create the application information
		 */
		String generated_client_name = null;
		String generated_client_id = null;
		String generated_client_secret = null;
		StringBuilder applicationName = new StringBuilder();
		applicationName.append(exchangeAssetName).append("_").append(environmentName);
		createApplication(client, authorizationHdr, myOrganizationId, applicationName.toString(), null);
		applications = getApplicationList(client, authorizationHdr, myOrganizationId);
		applicationInfo = null;
		for (LinkedHashMap<String, Object> e:applications) {
			if (e.get("name").equals(applicationName.toString())) {
				applicationInfo = getApplicationInformation(client, authorizationHdr, myOrganizationId, (int) e.get("id"));
				generated_client_name = (String) applicationInfo.get("name");
				generated_client_id = (String) applicationInfo.get("clientId");
				generated_client_secret = (String) applicationInfo.get("clientSecret");
				break;
			}
		}

		/*
		 * Add API Policies
		 */
		addApiPolicies(client, authorizationHdr, businessGroupId, environmentId, autoDiscoveryApiId, policies);
		getApiPolicies(client, authorizationHdr, businessGroupId, environmentId, autoDiscoveryApiId);
		
		/*
		 * Add application contracts
		 */
		createApplicationContracts(client, authorizationHdr, businessGroupId,
				businessGroupName, businessGroupId, environmentName, environmentId,
				exchangeAssetId, exchangeAssetVersion, autoDiscoveryApiId, apiVersion, clients,
				applications);
		
		// save configuration
		ArrayList<Object> empty = new ArrayList<Object>();
		returnPayload.put("imports", empty.toArray());
		returnPayloadProperties.put("secure.properties", "generated_client_secret,cps_client_secret,auto_api_registration_client_secret");
		returnPayloadProperties.put("apiName", apiName);
		returnPayloadProperties.put("apiManagerAssetId", apiManagerAssetId);
		returnPayloadProperties.put("apiVersion", apiVersion);
		returnPayloadProperties.put("exchangeAssetName", exchangeAssetName);
		returnPayloadProperties.put("exchangeAssetId", exchangeAssetId);
		returnPayloadProperties.put("exchangeAssetVersion", exchangeAssetVersion);
		returnPayloadProperties.put("exchangeAssetVersionGroup", (String) apiAsset.get("versionGroup"));
		returnPayloadProperties.put("exchangeAssetGroupId", (String) apiAsset.get("groupId"));
		returnPayloadProperties.put("exchangeAssetOrganizationId", (String) apiAsset.get("groupId"));
		returnPayloadProperties.put("auto-discovery-apiId", autoDiscoveryApiId);
		returnPayloadProperties.put("auto-discovery-apiName", autoDiscoveryApiName);
		returnPayloadProperties.put("auto-discovery-apiVersion", autoDiscoveryApiVersion);
		returnPayloadProperties.put("generated_client_name", generated_client_name);
		returnPayloadProperties.put("generated_client_id", generated_client_id);
		returnPayloadProperties.put("generated_client_secret", generated_client_secret);
		returnPayloadProperties.put("cps_client_name", cps_client_name);
		returnPayloadProperties.put("cps_client_id", cps_client_id);
		returnPayloadProperties.put("cps_client_secret", cps_client_secret);
		returnPayloadProperties.put("auto_api_registration_client_name", auto_reg_client_name);
		returnPayloadProperties.put("auto_api_registration_client_id", auto_reg_client_id);
		returnPayloadProperties.put("auto_api_registration_client_secret", auto_reg_client_secret);
		returnPayload.put("properties", returnPayloadProperties);

		String result = mapperw.writerWithDefaultPrettyPrinter().writeValueAsString(returnPayload);

		try {
			return encrypt(keyId, result);
		} catch (Exception e) {
			return result;
		}
	}

	@SuppressWarnings("unchecked")
	private static String getAPToken(Client restClient, String user, String password) throws JsonProcessingException {
		String token = null;
		LinkedHashMap<String, Object> loginValues = new LinkedHashMap<String, Object>();
		loginValues.put("username", user);
		loginValues.put("password", password);
		String payload = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(loginValues);
		WebTarget target = restClient.target(HTTPS_ANYPOINT_MULESOFT_COM).path("accounts/login");

		Response response = target.request().accept(MediaType.APPLICATION_JSON)
				.post(Entity.entity(payload, MediaType.APPLICATION_JSON));

		int statuscode = 500;
		Map<String, Object> result = null;
		if (response != null) {
			statuscode = response.getStatus();
		}
		if (response != null && response.getStatus() == 200) {
			result = response.readEntity(Map.class);
			token = (String) result.get("access_token");
		} else {
			System.err.println("Failed to login...check credentials");
			System.exit(statuscode);
		}

		return token;
	}

	@SuppressWarnings("unchecked")
	private static LinkedHashMap<String, Object> getMyInformation(Client restClient, String authorizationHdr)
			throws JsonProcessingException {
		WebTarget target = restClient.target(HTTPS_ANYPOINT_MULESOFT_COM).path("accounts/api/me");

		Response response = target.request().header("Authorization", authorizationHdr)
				.accept(MediaType.APPLICATION_JSON).get();

		int statuscode = 500;
		LinkedHashMap<String, Object> result = null;
		if (response != null) {
			statuscode = response.getStatus();
		}
		if (response != null && response.getStatus() == 200) {
			result = response.readEntity(LinkedHashMap.class);
		} else {
			System.err.println("Failed to get login profile");
			System.exit(statuscode);
		}

		// ObjectMapper mapperw = new ObjectMapper();
		// System.err.println("myInformation: " +
		// mapperw.writerWithDefaultPrettyPrinter().writeValueAsString(result));
		return result;
	}

	private static LinkedHashMap<String, Object> getBusinessGroupInformation(
			ArrayList<LinkedHashMap<String, Object>> memberOfOrganizations, String businessGroupName)
			throws JsonProcessingException {
		LinkedHashMap<String, Object> result = null;

		for (LinkedHashMap<String, Object> i : memberOfOrganizations) {
			if (i.get("name").equals(businessGroupName)) {
				result = i;
				break;
			}
		}

		if (result != null) {
			// ObjectMapper mapperw = new ObjectMapper();
			// System.err.println(
			// "businessGroupInformation: " +
			// mapperw.writerWithDefaultPrettyPrinter().writeValueAsString(result));
			return result;
		} else {
			System.err.println("Failed to find business Group information");
			System.exit(404);
			return null;
		}

	}

	@SuppressWarnings("unchecked")
	private static LinkedHashMap<String, Object> getEnvironmentInformation(Client restClient, String authorizationHdr,
			String businessGroupId, String environmentName) throws JsonProcessingException {
		WebTarget target = restClient.target(HTTPS_ANYPOINT_MULESOFT_COM).path("accounts/api/organizations")
				.path(businessGroupId).path("environments");

		Response response = target.request().header("Authorization", authorizationHdr)
				.accept(MediaType.APPLICATION_JSON).get();

		int statuscode = 500;
		LinkedHashMap<String, Object> result = null;
		if (response != null) {
			statuscode = response.getStatus();
		}
		if (response != null && response.getStatus() == 200) {
			result = response.readEntity(LinkedHashMap.class);
		} else {
			System.err.println("Failed to get environment information");
			System.exit(statuscode);
		}

		for (LinkedHashMap<String, Object> i : (ArrayList<LinkedHashMap<String, Object>>) result.get("data")) {
			if (i.get("name").equals(environmentName)) {
				result = i;
				break;
			}
		}

		if (result != null) {
			// ObjectMapper mapperw = new ObjectMapper();
			// System.err.println("environment: " +
			// mapperw.writerWithDefaultPrettyPrinter().writeValueAsString(result));
			return result;
		} else {
			System.err.println("Failed to find environment information");
			System.exit(404);
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	private static ArrayList<LinkedHashMap<String, Object>> getApplicationList(Client restClient, String authorizationHdr,
			String organizationId) throws JsonProcessingException {
		WebTarget target = restClient.target(HTTPS_ANYPOINT_MULESOFT_COM).path("exchange/api/v1/organizations")
				.path(organizationId).path("applications");

		Response response = target.request().header("Authorization", authorizationHdr)
				.accept(MediaType.APPLICATION_JSON).get();

		int statuscode = 500;
		ArrayList<LinkedHashMap<String, Object>> result = null;
		if (response != null) {
			statuscode = response.getStatus();
		}
		if (response != null && response.getStatus() == 200) {
			result = (ArrayList<LinkedHashMap<String, Object>>) response.readEntity(ArrayList.class);
		} else {
			System.err.println("Failed to get application list (" + statuscode + ")");
			System.exit(statuscode);
		}

		if (result != null) {
//			ObjectMapper mapperw = new ObjectMapper();
//			System.err.println("applications: " + mapperw.writerWithDefaultPrettyPrinter().writeValueAsString(result));
			return result;
		} else {
			System.err.println("Failed to find list of applications");
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	private static LinkedHashMap<String, Object> getApplicationInformation(Client restClient, String authorizationHdr,
			String organizationId, int applicationId) throws JsonProcessingException {
		WebTarget target = restClient.target(HTTPS_ANYPOINT_MULESOFT_COM).path("exchange/api/v1/organizations")
				.path(organizationId).path("applications").path(Integer.toString(applicationId));

		Response response = target.request().header("Authorization", authorizationHdr)
				.accept(MediaType.APPLICATION_JSON).get();

		int statuscode = 500;
		LinkedHashMap<String,Object> result = null;
		if (response != null) {
			statuscode = response.getStatus();
		}
		if (response != null && response.getStatus() == 200) {
			result = (LinkedHashMap<String, Object>) response.readEntity(LinkedHashMap.class);
		} else {
			System.err.println("Failed to get application information (" + statuscode + ")");
			System.exit(statuscode);
		}

		if (result != null) {
//			ObjectMapper mapperw = new ObjectMapper();
//			System.err.println("application: " + mapperw.writerWithDefaultPrettyPrinter().writeValueAsString(result));
			return result;
		} else {
			System.err.println("Failed to find application information");
			return null;
		}
	}

	private static void createApplication(Client restClient, String authorizationHdr,
			String organizationId, String applicationName, String description) throws JsonProcessingException {
		String desc = (description == null)
				? "Auto generated client credentials for this API instance to use calling other dependencies."
				: description;
		LinkedHashMap<String, Object> applicationValues = new LinkedHashMap<String, Object>();
		applicationValues.put("name", applicationName);
		applicationValues.put("description", desc);
		applicationValues.put("redirectUri", new ArrayList<String>());
		applicationValues.put("grantTypes", new ArrayList<String>());
		applicationValues.put("apiEndpoints", false);
		String payload = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(applicationValues);
		WebTarget target = restClient.target(HTTPS_ANYPOINT_MULESOFT_COM).path("exchange/api/v1/organizations")
				.path(organizationId).path("applications");

		Response response = target.request().header("Authorization", authorizationHdr)
				.accept(MediaType.APPLICATION_JSON).post(Entity.entity(payload, MediaType.APPLICATION_JSON));

		int statuscode = 500;
		if (response != null) {
			statuscode = response.getStatus();
		}
		if (response != null && (response.getStatus() == 201 || response.getStatus() == 409)) {
//			System.err.println(response.readEntity(String.class));
		} else {
			System.err.println("Failed to create application information (" + statuscode + ")");
			System.err.println(response.readEntity(String.class));
			System.exit(statuscode);
		}
	}

	private static void createApplicationContracts(Client restClient, String authorizationHdr, String organizationId,
			String businessGroupName, String businessGroupId, String environmentName, String environmentId,
			String exchangeAssetId, String exchangeAssetVersion, String autoDiscoveryApiId, String apiVersion, String contractsFileName,
			ArrayList<LinkedHashMap<String, Object>> applications) throws JsonProcessingException {

		ArrayList<LinkedHashMap<String, Object>> contracts;
		ObjectMapper mapper;
		TypeFactory factory;
		CollectionType type;

		factory = TypeFactory.defaultInstance();
		type = factory.constructCollectionType(ArrayList.class, LinkedHashMap.class);
		mapper = new ObjectMapper();

		InputStream is = null;
		File contractsFile = new File(contractsFileName);
		String contractsStr = null;
		try {
			if (contractsFile.exists()) {
				contractsStr = FileUtils.readFileToString(contractsFile, "UTF-8");
			} else {
				is = ApiDeployTool.class.getClassLoader().getResourceAsStream(contractsFileName);
				contractsStr = IOUtils.toString(is, "UTF-8");
			}
//			System.err.println(contractsStr);
			contracts = mapper.readValue(contractsStr, type);

			for (LinkedHashMap<String, Object> i : contracts) {
				int applicationId = 0;
				StringBuilder applicationName = new StringBuilder();
				applicationName.append(i.get("applicationName")).append("_").append(businessGroupName).append("_").append(environmentName);
//				System.err.println(applicationName.toString());
				for (LinkedHashMap<String, Object> e:applications) {
					if (e.get("name").equals(applicationName.toString())) {
						applicationId = (int) e.get("id");
						break;
					}
				}
				if (applicationId != 0) {
					createApplicationContract(restClient, authorizationHdr, organizationId, applicationId,
							businessGroupId, environmentId, exchangeAssetId, exchangeAssetVersion, autoDiscoveryApiId, apiVersion);
				} else {
					System.err.println("Could not find application in list: " + applicationName);
				}
			}

		} catch (Exception e) {
			System.err.println("Cannot use contracts file " + contractsFileName);
			e.printStackTrace(System.err);
		} finally {
			if (is != null) IOUtils.closeQuietly(is);
		}

	}

	private static void createApplicationContract(Client restClient, String authorizationHdr,
			String organizationId, int applicationId, String businessGroupId,
			String environmentId, String exchangeAssetId, String exchangeAssetVersion, String autoDiscoveryApiId, String apiVersion) throws JsonProcessingException {
		LinkedHashMap<String, Object> contractValues = new LinkedHashMap<String, Object>();
		contractValues.put("apiId", autoDiscoveryApiId);
		contractValues.put("environmentId", environmentId);
		contractValues.put("acceptedTerms", true);
		contractValues.put("organizationId", businessGroupId);
		contractValues.put("groupId", businessGroupId);
		contractValues.put("assetId", exchangeAssetId);
		contractValues.put("version", exchangeAssetVersion);
		contractValues.put("productAPIVersion", apiVersion);
		String payload = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(contractValues);
		
		WebTarget target = restClient.target(HTTPS_ANYPOINT_MULESOFT_COM).path("exchange/api/v1/organizations")
				.path(organizationId).path("applications").path(Integer.toString(applicationId)).path("contracts");

		Response response = target.request().header("Authorization", authorizationHdr)
				.accept(MediaType.APPLICATION_JSON).post(Entity.entity(payload, MediaType.APPLICATION_JSON));

		int statuscode = 500;
		if (response != null) {
			statuscode = response.getStatus();
		}
		if (response != null && (response.getStatus() == 201 || response.getStatus() == 409)) {
//			System.err.println(response.readEntity(String.class));
		} else {
			System.err.println("Failed to create application contract (" + statuscode + ")");
			System.err.println(response.readEntity(String.class));
		}
	}

	private static LinkedHashMap<String, Object> findApiAsset(ArrayList<LinkedHashMap<String, Object>> assetList,
			String organizationName, String groupName, String apiName, String apiVersion)
			throws JsonProcessingException {
		LinkedHashMap<String, Object> result = null;
		StringBuilder sb = new StringBuilder();
		sb.append(apiName).append("_").append(groupName);
		String name = sb.toString();

		for (LinkedHashMap<String, Object> i : assetList) {
			if (i.get("name").equals(name) && i.get("productAPIVersion").equals(apiVersion)) {
				result = i;
				break;
			}
		}

		if (result != null) {
//			ObjectMapper mapperw = new ObjectMapper();
//			System.err.println(
//					"existing Exchange asset: " + mapperw.writerWithDefaultPrettyPrinter().writeValueAsString(result));
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	private static ArrayList<LinkedHashMap<String, Object>> getExchangeAssets(Client restClient,
			String authorizationHdr, String businessGroupId, String name) throws JsonProcessingException {
		WebTarget target = restClient.target(HTTPS_ANYPOINT_MULESOFT_COM).path("exchange/api/v1/assets")
				.queryParam("search", name).queryParam("organizationId", businessGroupId).queryParam("limit", 400);

		Response response = target.request().header("Authorization", authorizationHdr)
				.accept(MediaType.APPLICATION_JSON).get();

		int statuscode = 500;
		ArrayList<LinkedHashMap<String, Object>> result = null;
		if (response != null) {
			statuscode = response.getStatus();
		}
		if (response != null && response.getStatus() == 200) {
			result = response.readEntity(ArrayList.class);
		} else {
			System.err.println("Failed to get Exchange assets (" + statuscode + ")");
			return null;
		}

		if (result != null) {
			// ObjectMapper mapperw = new ObjectMapper();
			// System.err.println("assets: " +
			// mapperw.writerWithDefaultPrettyPrinter().writeValueAsString(result));
			return result;
		} else {
			// System.err.println("Failed to find Exchange assets");
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	private static void publishAPItoExchange(Client restClient, String authorizationHdr, String apiName,
			String apiVersion, String organizationName, String organizationId, String groupName, String groupId)
			throws JsonProcessingException {

		String assetVersion = apiVersion;
		StringBuilder assetId = new StringBuilder();
		assetId.append(groupId).append("_").append(apiName).append("_").append(assetVersion);

		StringBuilder name = new StringBuilder();
		name.append(apiName).append("_").append(groupName);

		WebTarget target = restClient.target(HTTPS_ANYPOINT_MULESOFT_COM).path("exchange/api/v1/assets");

		FormDataMultiPart form = new FormDataMultiPart();
		form.setMediaType(MediaType.MULTIPART_FORM_DATA_TYPE);
		form.field("organizationId", groupId);
		form.field("groupId", groupId);
		form.field("assetId", assetId.toString());
		form.field("version", assetVersion);
		form.field("name", name.toString());
		form.field("apiVersion", apiVersion);
		form.field("classifier", "http");
		form.field("asset", "undefined");

		Response response = target.request().accept(MediaType.APPLICATION_JSON)
				.header("Authorization", authorizationHdr).post(Entity.entity(form, form.getMediaType()));

		int statuscode = 500;
		LinkedHashMap<String, Object> result = null;
		if (response != null) {
			statuscode = response.getStatus();
		}
		if (response != null && response.getStatus() == 201) {
			result = response.readEntity(LinkedHashMap.class);
		} else {
			System.err.println("Failed to post API to Exchange. (" + statuscode + ")");
			System.err.println(response.readEntity(String.class));

		}

		if (result != null) {
			// ObjectMapper mapperw = new ObjectMapper();
			// System.err.println(
			// "new Exchange asset: " +
			// mapperw.writerWithDefaultPrettyPrinter().writeValueAsString(result));
		} else {
			System.err.println("Failed to publish Exchange asset");
			System.exit(statuscode);
		}
	}

	@SuppressWarnings("unchecked")
	private static LinkedHashMap<String, Object> getApiManagerAsset(Client restClient, String authorizationHdr,
			String businessGroupId, String environmentId, String assetId, String assetVersion)
			throws JsonProcessingException {
		WebTarget target = restClient.target(HTTPS_ANYPOINT_MULESOFT_COM).path("apimanager/api/v1/organizations")
				.path(businessGroupId).path("environments").path(environmentId).path("apis")
				.queryParam("assetId", assetId).queryParam("assetVersion", assetVersion).queryParam("limit", 400)
				.queryParam("instanceLabel", "auto-api-registation-" + assetId);

		Response response = target.request().header("Authorization", authorizationHdr)
				.accept(MediaType.APPLICATION_JSON).get();

		int statuscode = 500;
		LinkedHashMap<String, Object> result = null;
		ArrayList<LinkedHashMap<String, Object>> apiManagerAssets = null;
		if (response != null) {
			statuscode = response.getStatus();
		}
		if (response != null && response.getStatus() == 200) {
			LinkedHashMap<String, Object> apis = (LinkedHashMap<String, Object>) response.readEntity(LinkedHashMap.class);
			apiManagerAssets = (ArrayList<LinkedHashMap<String, Object>>) apis.get("assets");
			if (apiManagerAssets != null && !apiManagerAssets.isEmpty()) {
				result = apiManagerAssets.get(0);
			}
		} else {
			System.err.println("Failed to get API Manager asset (" + statuscode + ")");
			return null;
		}

		if (result != null) {
//			System.err.println("Using existing API Manager asset " + result.get("id") + ".");
//			ObjectMapper mapperw = new ObjectMapper();
//			System.err.println("api Instance: " + mapperw.writerWithDefaultPrettyPrinter().writeValueAsString(result));
			return result;
		} else {
//			System.err.println("No existing API Manager asset found.");
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	private static void registerAPIInstance(Client restClient, String authorizationHdr, String businessGroupId,
			String environmentId, String assetId, String assetVersion) throws JsonProcessingException {
		HashMap<String, Object> body = new HashMap<String, Object>();
		LinkedHashMap<String, Object> specValues = new LinkedHashMap<String, Object>();
		specValues.put("groupId", businessGroupId);
		specValues.put("assetId", assetId);
		specValues.put("version", assetVersion);
		body.put("spec", specValues);
		body.put("instanceLabel", "auto-api-registation-" + assetId);
		LinkedHashMap<String, Object> endpointValues = new LinkedHashMap<String, Object>();
		endpointValues.put("uri", "https://some.implementation.com");
		endpointValues.put("proxyUri", null);
		endpointValues.put("isCloudHub", false);
		body.put("endpoint", endpointValues);

		String payload = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(body);
		WebTarget target = restClient.target(HTTPS_ANYPOINT_MULESOFT_COM).path("apimanager/api/v1/organizations")
				.path(businessGroupId).path("environments").path(environmentId).path("apis");

		Response response = target.request().accept(MediaType.APPLICATION_JSON)
				.header("Authorization", authorizationHdr).post(Entity.entity(payload, MediaType.APPLICATION_JSON));

		int statuscode = 500;
		LinkedHashMap<String, Object> result = null;
		if (response != null) {
			statuscode = response.getStatus();
		}
		if (response != null && response.getStatus() == 201) {
			result = response.readEntity(LinkedHashMap.class);
		} else {
			System.err.println("Failed to register API to API Manager. (" + statuscode + ")");
			System.err.println(response.readEntity(String.class));
		}

		if (result != null) {
//			ObjectMapper mapperw = new ObjectMapper();
//			System.err.println(
//					"new API instance: " + mapperw.writerWithDefaultPrettyPrinter().writeValueAsString(result));
		} else {
			System.err.println("Failed to create API instance");
			System.exit(statuscode);
		}
	}

	@SuppressWarnings("unchecked")
	private static ArrayList<LinkedHashMap<String, Object>> getApiPolicies(Client restClient, String authorizationHdr,
			String businessGroupId, String environmentId, String apiInstanceId) throws JsonProcessingException {
		WebTarget target = restClient.target(HTTPS_ANYPOINT_MULESOFT_COM).path("apimanager/api/v1/organizations")
				.path(businessGroupId).path("environments").path(environmentId).path("apis").path(apiInstanceId)
				.path("policies");

		Response response = target.request().header("Authorization", authorizationHdr)
				.accept(MediaType.APPLICATION_JSON).get();

		int statuscode = 500;
		ArrayList<LinkedHashMap<String, Object>> result = null;
		if (response != null) {
			statuscode = response.getStatus();
		}
		if (response != null && response.getStatus() == 200) {
			result = (ArrayList<LinkedHashMap<String, Object>>) response.readEntity(ArrayList.class);
		} else {
			System.err.println("Failed to get API policies (" + statuscode + ")");
			return null;
		}

		if (result != null) {
//			ObjectMapper mapperw = new ObjectMapper();
//			System.err.println("api policies: " + mapperw.writerWithDefaultPrettyPrinter().writeValueAsString(result));
			return result;
		} else {
			System.err.println("Failed to find API policies");
			return null;
		}
	}

	private static void addApiPolicies(Client restClient, String authorizationHdr, String businessGroupId,
			String environmentId, String apiInstanceId, String apiPolicies) {

		ArrayList<LinkedHashMap<String, Object>> policies;
		ObjectMapper mapper;
		TypeFactory factory;
		CollectionType type;

		factory = TypeFactory.defaultInstance();
		type = factory.constructCollectionType(ArrayList.class, LinkedHashMap.class);
		mapper = new ObjectMapper();

		InputStream is = null;
		File policyFile = new File(apiPolicies);
		String policiesStr = null;
		try {
			if (policyFile.exists()) {
				policiesStr = FileUtils.readFileToString(policyFile, "UTF-8");
			} else {
				is = ApiDeployTool.class.getClassLoader().getResourceAsStream(apiPolicies);
				policiesStr = IOUtils.toString(is, "UTF-8");
			}
//			System.err.println(policiesStr);
			policies = mapper.readValue(policiesStr, type);

			for (LinkedHashMap<String, Object> i : policies) {
				addApiPolicy(restClient, authorizationHdr, businessGroupId, environmentId, apiInstanceId, i);
			}

		} catch (Exception e) {
			System.err.println("Cannot use policies from file " + apiPolicies);
			e.printStackTrace(System.err);
			System.exit(1);
		} finally {
			if (is != null) IOUtils.closeQuietly(is);
		}

	}

	private static void addApiPolicy(Client restClient, String authorizationHdr, String businessGroupId,
			String environmentId, String apiInstanceId, LinkedHashMap<String, Object> apiPolicy)
			throws JsonProcessingException {

		String policyStr = null;
		try {
			ObjectMapper mapperw = new ObjectMapper();
			policyStr = mapperw.writeValueAsString(apiPolicy);
//			System.err.println("Setting policy " + policyStr);
			WebTarget target = restClient.target(HTTPS_ANYPOINT_MULESOFT_COM).path("apimanager/api/v1/organizations")
					.path(businessGroupId).path("environments").path(environmentId).path("apis").path(apiInstanceId)
					.path("policies");

			Response response = target.request().accept(MediaType.APPLICATION_JSON)
					.header("Authorization", authorizationHdr)
					.post(Entity.entity(policyStr, MediaType.APPLICATION_JSON));

			int statuscode = 500;
			if (response != null) {
				statuscode = response.getStatus();
			}
			if (response != null && (response.getStatus() == 201 || response.getStatus() == 409)) {
//				System.err.println(response.readEntity(String.class));
			} else {
				System.err.println("Failed to apply policy " + policyStr + ". (" + statuscode + ")");
				System.err.println(response.readEntity(String.class));
			}
		} catch (Exception e) {
			System.err.println("Cannot set policy:\n " + policyStr);
			e.printStackTrace(System.err);
		}
	}

	@SuppressWarnings("unchecked")
	private static String encrypt(String argKeyId, String data) throws Exception {
		Map<String, Object> payload;
		ObjectMapper mapper;
		TypeFactory factory;
		MapType type;

		factory = TypeFactory.defaultInstance();
		type = factory.constructMapType(LinkedHashMap.class, String.class, Object.class);
		mapper = new ObjectMapper();
		payload = mapper.readValue(data, type);

		/* Start determining keyId */
		String keyId = (String) payload.get("keyId");
		String cipherKey = (String) payload.get("cipherKey");
		if (keyId == null || keyId.isEmpty()) {
			keyId = argKeyId;
		}
		if (keyId == null || keyId.isEmpty()) {
			String msg = "Need a keyId to be specified in the configuration file, cannot continue with encrypt";
			System.err.println(msg);
			throw new Exception(msg);
		}

		Map<String, String> properties = (Map<String, String>) payload.get("properties");
		boolean priorEncryptions = false;
		for (String key : properties.keySet()) {
			String value = properties.get(key);
			if (value.startsWith("![")) {
				priorEncryptions = true;
			}
		}
		if (!priorEncryptions && argKeyId != null) {
			keyId = argKeyId;
			payload.put("keyId", keyId);
		}
		/* End determining keyId */

		/* Start determining cipherKey */
		boolean newCipherKeyGenerated = false;
		CpsEncryptor cpsEncryptor = null;
		if (cipherKey == null || cipherKey.isEmpty()) {
			String msg = "Generating a new cipherKey for encryption";
			System.err.println(msg);
			cpsEncryptor = new CpsEncryptor(keyId);
			cipherKey = cpsEncryptor.getCpsKey().getCipherKey();
			newCipherKeyGenerated = true;
		} else {
			cpsEncryptor = new CpsEncryptor(keyId, cipherKey);
		}
		/* End determining cipherKey */

		String securePropertyList = properties.get("secure.properties");
		if (securePropertyList == null || securePropertyList.isEmpty()) {
			/* Nothing to do */
			System.err.println("No properties listed in secure.properties");
		} else {
			String[] secureProperties = securePropertyList.split(",");
			boolean canEncrypt = true;
			for (String key : secureProperties) {
				String plainTextValue = properties.get(key.trim());
				if (cpsEncryptor.isEncrypted(plainTextValue)) {
					System.err.println(key.trim() + " is already encrypted.");
					canEncrypt = (newCipherKeyGenerated) ? false : true;
				}
			}

			if (!canEncrypt) {
				String msg = "Input configuration file has problems with prior encryptions, cannot continue the encrypt";
				System.err.println(msg);
				throw new Exception(msg);
			}

			boolean propertiesChanged = false;
			for (String key : secureProperties) {
				String plainTextValue = properties.get(key.trim());
				String value = cpsEncryptor.encrypt(plainTextValue);
				properties.put(key, value);
				propertiesChanged = true;
			}
			if (propertiesChanged) {
				if (newCipherKeyGenerated) {
					payload.put("cipherKey", cipherKey);
				}
				payload.put("properties", properties);
			} else {
				System.err.println("No properties encrypted");
			}
		}

		return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(payload);
	}
}
