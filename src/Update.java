import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

public class Update {

	public static boolean requestUpdate(Client client, AdeptConnection c) throws ClientRequestException {
		String uName = client.getUname();
		String msg;
		String resp;
		SQLiteInterface db = client.getDB();
		
		ArrayList<String> extraUpdates = new ArrayList<String>();
		
		try {
			msg = String.format("LIST %s", uName);
			resp = c.request(msg);
			HashMap<String, String> emailsMailboxes = new HashMap<String, String>();
			ArrayList<String> remoteMailboxes = new ArrayList<String>();
			ArrayList<String> remoteEmails = new ArrayList<String>();
			
			//Get mailboxes
			if (resp.startsWith("OK - List Completed") && resp.split("\n").length > 1) {
				ArrayList<String> mailboxes = new ArrayList<String>(Arrays.asList(Arrays.copyOfRange(resp.split("\n"), 1, resp.split("\n").length)));
				for (String mailbox : mailboxes) {
					remoteMailboxes.add(mailbox);
					//Update local db with new mailboxes
					if (!db.mailboxExists(mailbox, uName)) db.createMailbox(mailbox, uName);
					//Get email_ids
					msg = String.format("LIST %s %s", uName, mailbox);
					resp = c.request(msg);
					if (resp.startsWith("OK - List Completed") && resp.split("\n").length > 1) {
						ArrayList<String> email_ids = new ArrayList<String>(Arrays.asList(Arrays.copyOfRange(resp.split("\n"), 1, resp.split("\n").length)));
						for (String email_id : email_ids) {
							emailsMailboxes.put(email_id, db.getMailboxID(mailbox, uName));
							remoteEmails.add(email_id);
						}
					}
				}
			}
			
			//Get list of local emails
			ArrayList<String> localEmails = db.getEmailIds(uName);
			
			//Make sure mailboxes are up to date
			for (String localEmail : localEmails) {
				String curMailbox = db.getEmailMailbox(localEmail);
				String canonMailbox = emailsMailboxes.get(localEmail);
				if (!curMailbox.equals(canonMailbox)) extraUpdates.add(localEmail);
			}
			
			ArrayList<String> failures = new ArrayList<String>();
			boolean failed = false;
			for (String email_id : emailsMailboxes.keySet()) {
				String mailbox = emailsMailboxes.get(email_id);
				HashMap<String, String> emailData = new HashMap<String, String>();
				emailData.put("email_id", email_id);
				emailData.put("mailbox", mailbox);
				String okString = "OK - fetch completed";
				
				//If we don't have this email locally, or it needs an update, fetch it and store it
				if (!localEmails.contains(email_id) || extraUpdates.contains(email_id)) {
					msg = String.format("FETCH %s BODY[HEADER]", email_id);
					resp = c.request(msg);
					//Get the headers
					if (resp.startsWith(okString) && resp.split("\n").length > 1) {
						ArrayList<String> headers = new ArrayList<String>(Arrays.asList(Arrays.copyOfRange(resp.split("\n"), 1, resp.split("\n").length)));
						ArrayList<String> validDatas = new ArrayList<String>(Arrays.asList(new String[] {"date", "to", "from", "subject"}));
						for (String header : headers) {
							String[] headerParts = header.split(": ");
							if(headerParts.length == 2) {
								String field = headerParts[0].toLowerCase();
								String value = headerParts[1];
								if (validDatas.contains(field)) emailData.put(field, value.replace("'", "''"));
							}
						}
						
					}
					
					//Get the message body
					msg = String.format("FETCH %s BODY[TEXT]", email_id);
					resp = c.request(msg);
					if (resp.startsWith(okString) && resp.length() > okString.length()) {
						String body = resp.substring(okString.length(), resp.length()).trim();
						emailData.put("body", body.replace("'", "''"));
					}

					//Get whether it is read or unread
					//TODO this is not interoperable with other IMAP servers. It's assuming only one flag
					msg = String.format("FETCH %s FLAGS", email_id);
					resp = c.request(msg);
					if (resp.startsWith(okString) && resp.length() > okString.length()) {
						String respString = resp.substring(okString.length(), resp.length()).trim();
						switch (respString) {
							case "READ":
								emailData.put("read", "t");
								break;
							case "UNREAD":
								emailData.put("read", "f");
								break;
							default:
								emailData.put("read", "f");
						}
					}
					
					//Check to make sure we have all the headers, body, and read flag
					//Then update the local db with the information
					if (emailData.keySet().size() == 8) {
						try {
							db.addEmail(emailData);
						} catch (ClientRequestException e) {
							System.out.println("Update.java ClientRequestException on db.addEmail(emailData)");
							System.out.println(e.getMessage());
							e.printStackTrace();
							//Hacky but this is still happening for some reason...
							if (!e.getMessage().startsWith("[SQLITE_CONSTRAINT_PRIMARYKEY]")) {
								failed = true;
								failures.add(email_id);
							}
						}
					}
				}
			}
			
			//Delete emails that shouldn't exist anymore
			for (String localEmail : localEmails) {
				if (!remoteEmails.contains(localEmail)) {
					db.deleteEmail(localEmail);
				}
			}
			
			//Delete mailboxes that shouldn't exist anymore
			ArrayList<String> localMailboxes = db.getAllMailboxNames(uName);
			for (String localMailbox : localMailboxes) {
				if (!remoteMailboxes.contains(localMailbox)) {
					String mailbox_id = db.getMailboxID(localMailbox, client.getUname());
					db.deleteMailbox(mailbox_id);
				}
			}
			if (!failed) return true;
			else {
				String failString = String.format("Failed to update email ids: ", String.join(", ", failures));
				throw new ClientRequestException(failString);
			}
		} catch (ClientRequestException e) {
			throw e;
		}
	}
	
	
}
