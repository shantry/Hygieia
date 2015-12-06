package com.capitalone.dashboard.collector;

import com.capitalone.dashboard.model.Commit;
import com.capitalone.dashboard.model.GitRepo;
import com.capitalone.dashboard.util.Supplier;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.joda.time.DateTime;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestOperations;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.TimeZone;

/**
 * BitbucketClient implementation that uses SVNKit to fetch information about
 * Subversion repositories.
 */

@Component
public class DefaultBitbucketClient implements GitClient {
	private static final Log LOG = LogFactory.getLog(DefaultBitbucketClient.class);

	private static final int FIRST_RUN_HISTORY_DEFAULT = 14;

	private final GitSettings settings;

	private final RestOperations restOperations;

	@Autowired
	public DefaultBitbucketClient(GitSettings settings,
			Supplier<RestOperations> restOperationsSupplier) {
		this.settings = settings;
		this.restOperations = restOperationsSupplier.get();
	}

	@Override
	public List<Commit> getCommits(GitRepo repo, boolean firstRun) {

		List<Commit> commits = new ArrayList<>();

		// format URL
		String repoUrl = (String) repo.getOptions().get("url");
		if (repoUrl.endsWith(".git")) {
			repoUrl = repoUrl.substring(0, repoUrl.lastIndexOf(".git"));
		}
		URL url = null;
		String hostName = "";
		String protocol = "";
		try {
			url = new URL(repoUrl);
			hostName = url.getHost();
			protocol = url.getProtocol();
		} catch (MalformedURLException e) {
			// TODO Auto-generated catch block
			LOG.error(e.getMessage());
		}
		String hostUrl = protocol + "://" + hostName + "/";
		String repoName = repoUrl.substring(hostUrl.length(), repoUrl.length());
		String apiUrl = "";
		if (hostName.startsWith(settings.getHost())) {
			apiUrl = protocol + "://" + settings.getHost() + repoName;
		} else {
			apiUrl = protocol + "://" + hostName + settings.getApi() + repoName;
			LOG.debug("API URL IS:"+apiUrl);
		}
		Date dt;
		if (firstRun) {
			int firstRunDaysHistory = settings.getFirstRunHistoryDays();
			if (firstRunDaysHistory > 0) {
				dt = getDate(new Date(), -firstRunDaysHistory, 0);
			} else {
				dt = getDate(new Date(), -FIRST_RUN_HISTORY_DEFAULT, 0);
			}
		} else {
			dt = getDate(repo.getLastUpdateTime(), 0, -10);
		}
		Calendar calendar = new GregorianCalendar();
		TimeZone timeZone = calendar.getTimeZone();
		Calendar cal = Calendar.getInstance(timeZone);
		cal.setTime(dt);
		String thisMoment = String.format("%tFT%<tRZ", cal);

		String queryUrl = apiUrl.concat("/commits?sha=" + repo.getBranch()
				+ "&since=" + thisMoment);
		/*
		 * Calendar cal = Calendar.getInstance(); cal.setTime(dateInstance);
		 * cal.add(Calendar.DATE, -30); Date dateBefore30Days = cal.getTime();
		 */

		boolean lastPage = false;
		int pageNumber = 1;
		String queryUrlPage = queryUrl;
		while (!lastPage) {
			try {
				ResponseEntity<String> response = makeRestCall(queryUrlPage, settings.getUser(), settings.getPassword());
				JSONObject jsonParentObject = paresAsObject(response);
				JSONArray jsonArray = (JSONArray) jsonParentObject.get("values");

				for (Object item : jsonArray) {
					JSONObject jsonObject = (JSONObject) item;
					String sha = str(jsonObject, "hash");
					JSONObject authorObject = (JSONObject) jsonObject.get("author");
					String message = str(jsonObject, "message");
					String author = str(authorObject, "raw");
					long timestamp = new DateTime(str(jsonObject, "date")).getMillis();
					
					Commit commit = new Commit();
					commit.setTimestamp(System.currentTimeMillis());
					commit.setScmUrl(repo.getRepoUrl());
					commit.setScmRevisionNumber(sha);
					commit.setScmAuthor(author);
					commit.setScmCommitLog(message);
					commit.setScmCommitTimestamp(timestamp);
					commit.setNumberOfChanges(1);
					commits.add(commit);
				}
				if (jsonArray == null || jsonArray.isEmpty()) {
					lastPage = true;
				} else {
					lastPage = isThisLastPage(response);
					pageNumber++;
					queryUrlPage = queryUrl + "&page=" + pageNumber;
				}

			} catch (RestClientException re) {
				LOG.error(re.getMessage() + ":" + queryUrl);
				lastPage = true;

			}
		}
		return commits;
	}

	private Date getDate(Date dateInstance, int offsetDays, int offsetMinutes) {
		Calendar cal = Calendar.getInstance();
		cal.setTime(dateInstance);
		cal.add(Calendar.DATE, offsetDays);
		cal.add(Calendar.MINUTE, offsetMinutes);
		return cal.getTime();
	}

	private boolean isThisLastPage(ResponseEntity<String> response) {
		HttpHeaders header = response.getHeaders();
		List<String> link = header.get("Link");
		if (link == null || link.isEmpty()) {
			return true;
		} else {
			for (String l : link) {
				if (l.contains("rel=\"next\"")) {
					return false;
				}

			}
		}
		return true;
	}

	private ResponseEntity<String> makeRestCall(String url, String userId,
			String password) {
		// Basic Auth only.
		if (!"".equals(userId) && !"".equals(password)) {
			return restOperations.exchange(url, HttpMethod.GET,
					new HttpEntity<>(createHeaders(userId, password)),
					String.class);

		} else {
			return restOperations.exchange(url, HttpMethod.GET, null,
					String.class);
		}

	}

	private HttpHeaders createHeaders(final String userId, final String password) {
		return new HttpHeaders() {
			private static final long serialVersionUID = 2242586825943479715L;

			{
				String auth = userId + ":" + password;
				byte[] encodedAuth = Base64.encodeBase64(auth.getBytes(Charset
						.forName("US-ASCII")));
				String authHeader = "Basic " + new String(encodedAuth);
				set("Authorization", authHeader);
			}
		};
	}
	
	private JSONObject paresAsObject(ResponseEntity<String> response) {
		try {
			return (JSONObject) new JSONParser().parse(response.getBody());
		} catch (ParseException pe) {
			LOG.error(pe.getMessage());
		}
		return new JSONObject();
	}

	private String str(JSONObject json, String key) {
		Object value = json.get(key);
		return value == null ? null : value.toString();
	}

}