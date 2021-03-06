package smithereen.routes;

import org.jetbrains.annotations.Nullable;

import java.net.URLEncoder;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

import static smithereen.Utils.*;

import smithereen.Config;
import smithereen.Utils;
import smithereen.activitypub.ActivityPubWorker;
import smithereen.data.Account;
import smithereen.data.ForeignUser;
import smithereen.data.FriendRequest;
import smithereen.data.FriendshipStatus;
import smithereen.data.PhotoSize;
import smithereen.data.Post;
import smithereen.data.SessionInfo;
import smithereen.data.User;
import smithereen.data.UserInteractions;
import smithereen.data.WebDeltaResponseBuilder;
import smithereen.data.notifications.Notification;
import smithereen.lang.Lang;
import smithereen.storage.MediaStorageUtils;
import smithereen.storage.NotificationsStorage;
import smithereen.storage.PostStorage;
import smithereen.storage.UserStorage;
import smithereen.templates.RenderedTemplateResponse;
import spark.Request;
import spark.Response;
import spark.utils.StringUtils;

public class ProfileRoutes{
	public static Object profile(Request req, Response resp) throws SQLException{
		SessionInfo info=Utils.sessionInfo(req);
		@Nullable Account self=info!=null ? info.account : null;
		String username=req.params(":username");
		User user=UserStorage.getByUsername(username);
		if(user!=null){
			int[] postCount={0};
			int offset=Utils.parseIntOrDefault(req.queryParams("offset"), 0);
			List<Post> wall=PostStorage.getUserWall(user.id, 0, 0, offset, postCount);
			RenderedTemplateResponse model=new RenderedTemplateResponse("profile").with("title", user.getFullName()).with("user", user).with("wall", wall).with("own", self!=null && self.user.id==user.id).with("postCount", postCount[0]);
			model.with("pageOffset", offset);

			List<Integer> postIDs=wall.stream().map((Post p)->p.id).collect(Collectors.toList());
			HashMap<Integer, UserInteractions> interactions=PostStorage.getPostInteractions(postIDs, self!=null ? self.user.id : 0);
			model.with("postInteractions", interactions);

			int[] friendCount={0};
			List<User> friends=UserStorage.getRandomFriendsForProfile(user.id, friendCount);
			model.with("friendCount", friendCount[0]).with("friends", friends);
			if(info!=null && self!=null){
				model.with("draftAttachments", info.postDraftAttachments);
			}
			if(self!=null){
				if(user.id==self.user.id){
					// lang keys for profile picture update UI
					jsLangKey(req, "update_profile_picture", "save", "profile_pic_select_square_version", "drag_or_choose_file", "choose_file",
							"drop_files_here", "picture_too_wide", "picture_too_narrow", "ok", "error", "error_loading_picture",
							"remove_profile_picture", "confirm_remove_profile_picture");
				}else{
					FriendshipStatus status=UserStorage.getFriendshipStatus(self.user.id, user.id);
					if(status==FriendshipStatus.FRIENDS){
						model.with("isFriend", true);
						model.with("friendshipStatusText", Utils.lang(req).get("X_is_your_friend", user.firstName));
					}else if(status==FriendshipStatus.REQUEST_SENT){
						model.with("friendRequestSent", true);
						model.with("friendshipStatusText", Utils.lang(req).get("you_sent_friend_req_to_X", user.firstName));
					}else if(status==FriendshipStatus.REQUEST_RECVD){
						model.with("friendRequestRecvd", true);
						model.with("friendshipStatusText", Utils.lang(req).gendered("X_sent_you_friend_req", user.gender, user.firstName));
					}else if(status==FriendshipStatus.FOLLOWING){
						model.with("following", true);
						model.with("friendshipStatusText", Utils.lang(req).get("you_are_following_X", user.firstName));
					}else if(status==FriendshipStatus.FOLLOWED_BY){
						model.with("followedBy", true);
						model.with("friendshipStatusText", Utils.lang(req).gendered("X_is_following_you", user.gender, user.firstName));
					}else if(status==FriendshipStatus.FOLLOW_REQUESTED){
						model.with("followRequested", true);
						model.with("friendshipStatusText", Utils.lang(req).get("waiting_for_X_to_accept_follow_req", user.firstName));
					}
				}
			}else{
				HashMap<String, String> meta=new LinkedHashMap<>();
				meta.put("og:type", "profile");
				meta.put("og:site_name", Config.serverDisplayName);
				meta.put("og:title", user.getFullName());
				meta.put("og:url", user.url.toString());
				meta.put("og:username", user.getFullUsername());
				if(StringUtils.isNotEmpty(user.firstName))
					meta.put("og:first_name", user.firstName);
				if(StringUtils.isNotEmpty(user.lastName))
					meta.put("og:last_name", user.lastName);
				Lang l=Utils.lang(req);
				String descr=l.plural("X_friends", friendCount[0])+", "+l.plural("X_posts", postCount[0]);
				if(StringUtils.isNotEmpty(user.summary))
					descr+="\n"+user.summary;
				meta.put("og:description", descr);
				if(user.gender==User.Gender.MALE)
					meta.put("og:gender", "male");
				else if(user.gender==User.Gender.FEMALE)
					meta.put("og:gender", "female");
				if(user.hasAvatar()){
					PhotoSize size=MediaStorageUtils.findBestPhotoSize(user.getAvatar(), PhotoSize.Format.JPEG, PhotoSize.Type.XLARGE);
					if(size!=null){
						meta.put("og:image", size.src.toString());
						meta.put("og:image:width", size.width+"");
						meta.put("og:image:height", size.height+"");
					}
				}
				model.with("metaTags", meta);
			}
			Utils.jsLangKey(req, "yes", "no", "delete_post", "delete_post_confirm", "remove_friend", "confirm_unfriend_X", "cancel", "delete");
			return model.renderToString(req);
		}else{
			resp.status(404);
			return Utils.wrapError(req, resp, "err_user_not_found");
		}
	}

	public static Object confirmSendFriendRequest(Request req, Response resp, Account self) throws SQLException{
		req.attribute("noHistory", true);
		String username=req.params(":username");
		User user=UserStorage.getByUsername(username);
		if(user!=null){
			if(user.id==self.user.id){
				return wrapError(req, resp, "err_cant_friend_self");
			}
			FriendshipStatus status=UserStorage.getFriendshipStatus(self.user.id, user.id);
			Lang l=lang(req);
			if(status==FriendshipStatus.FOLLOWED_BY){
				if(isAjax(req) && verifyCSRF(req, resp)){
					UserStorage.followUser(self.user.id, user.id, true);
					return new WebDeltaResponseBuilder(resp).refresh().json();
				}else{
					RenderedTemplateResponse model=new RenderedTemplateResponse("form_page");
					model.with("targetUser", user);
					model.with("contentTemplate", "send_friend_request").with("formAction", user.getProfileURL("doSendFriendRequest")).with("submitButton", l.get("send"));
					return model.renderToString(req);
				}
			}else if(status==FriendshipStatus.NONE){
				RenderedTemplateResponse model=new RenderedTemplateResponse("send_friend_request");
				model.with("targetUser", user);
				return wrapForm(req, resp, "send_friend_request", user.getProfileURL("doSendFriendRequest"), l.get("add_friend"), "send", model);
			}else if(status==FriendshipStatus.FRIENDS){
				return wrapError(req, resp, "err_already_friends");
			}else if(status==FriendshipStatus.REQUEST_RECVD){
				return wrapError(req, resp, "err_have_incoming_friend_req");
			}else{ // REQ_SENT
				return wrapError(req, resp, "err_friend_req_already_sent");
			}
		}else{
			resp.status(404);
			return wrapError(req, resp, "user_not_found");
		}
	}

	public static Object doSendFriendRequest(Request req, Response resp, Account self) throws SQLException{
		String username=req.params(":username");
		User user=UserStorage.getByUsername(username);
		if(user!=null){
			if(user.id==self.user.id){
				return Utils.wrapError(req, resp, "err_cant_friend_self");
			}
			FriendshipStatus status=UserStorage.getFriendshipStatus(self.user.id, user.id);
			if(status==FriendshipStatus.NONE || status==FriendshipStatus.FOLLOWED_BY){
				if(status==FriendshipStatus.NONE)
					UserStorage.putFriendRequest(self.user.id, user.id, req.queryParams("message"), true);
				else
					UserStorage.followUser(self.user.id, user.id, true);
				if(isAjax(req)){
					resp.type("application/json");
					return new WebDeltaResponseBuilder().refresh().json();
				}
				resp.redirect(Utils.back(req));
				return "";
			}else if(status==FriendshipStatus.FRIENDS){
				return Utils.wrapError(req, resp, "err_already_friends");
			}else if(status==FriendshipStatus.REQUEST_RECVD){
				return Utils.wrapError(req, resp, "err_have_incoming_friend_req");
			}else{ // REQ_SENT
				return Utils.wrapError(req, resp, "err_friend_req_already_sent");
			}
		}else{
			resp.status(404);
			return Utils.wrapError(req, resp, "user_not_found");
		}
	}

	public static Object confirmRemoveFriend(Request req, Response resp, Account self) throws SQLException{
		req.attribute("noHistory", true);
		String username=req.params(":username");
		User user=UserStorage.getByUsername(username);
		if(user!=null){
			FriendshipStatus status=UserStorage.getFriendshipStatus(self.user.id, user.id);
			if(status==FriendshipStatus.FRIENDS || status==FriendshipStatus.REQUEST_SENT || status==FriendshipStatus.FOLLOWING){
				Lang l=Utils.lang(req);
				String back=Utils.back(req);
				return new RenderedTemplateResponse("generic_confirm").with("message", l.get("confirm_unfriend_X", escapeHTML(user.getFullName()))).with("formAction", user.getProfileURL("doRemoveFriend")+"?_redir="+URLEncoder.encode(back)).with("back", back).renderToString(req);
			}else{
				return Utils.wrapError(req, resp, "err_not_friends");
			}
		}else{
			resp.status(404);
			return Utils.wrapError(req, resp, "user_not_found");
		}
	}

	public static Object friends(Request req, Response resp) throws SQLException{
		String username=req.params(":username");
		User user;
		if(username==null){
			if(requireAccount(req, resp)){
				user=sessionInfo(req).account.user;
			}else{
				return "";
			}
		}else{
			user=UserStorage.getByUsername(username);
		}
		if(user!=null){
			RenderedTemplateResponse model=new RenderedTemplateResponse("friends");
			model.with("friendList", UserStorage.getFriendListForUser(user.id)).with("owner", user).with("tab", 0);
			jsLangKey(req, "remove_friend", "confirm_unfriend_X", "yes", "no");
			return model.renderToString(req);
		}
		resp.status(404);
		return Utils.wrapError(req, resp, "user_not_found");
	}

	public static Object followers(Request req, Response resp) throws SQLException{
		String username=req.params(":username");
		User user;
		if(username==null){
			if(requireAccount(req, resp)){
				user=sessionInfo(req).account.user;
			}else{
				return "";
			}
		}else{
			user=UserStorage.getByUsername(username);
		}
		if(user!=null){
			RenderedTemplateResponse model=new RenderedTemplateResponse("friends");
			model.with("friendList", UserStorage.getNonMutualFollowers(user.id, true, true)).with("owner", user).with("followers", true).with("tab", 1);
			return model.renderToString(req);
		}
		resp.status(404);
		return Utils.wrapError(req, resp, "user_not_found");
	}

	public static Object following(Request req, Response resp) throws SQLException{
		String username=req.params(":username");
		User user;
		if(username==null){
			if(requireAccount(req, resp)){
				user=sessionInfo(req).account.user;
			}else{
				return "";
			}
		}else{
			user=UserStorage.getByUsername(username);
		}
		if(user!=null){
			RenderedTemplateResponse model=new RenderedTemplateResponse("friends");
			model.with("friendList", UserStorage.getNonMutualFollowers(user.id, false, true)).with("owner", user).with("following", true).with("tab", 2);
			jsLangKey(req, "unfollow", "confirm_unfollow_X", "yes", "no");
			return model.renderToString(req);
		}
		resp.status(404);
		return Utils.wrapError(req, resp, "user_not_found");
	}

	public static Object incomingFriendRequests(Request req, Response resp, Account self) throws SQLException{
		List<FriendRequest> requests=UserStorage.getIncomingFriendRequestsForUser(self.user.id, 0, 100);
		RenderedTemplateResponse model=new RenderedTemplateResponse("friend_requests");
		model.with("friendRequests", requests);
		return model.renderToString(req);
	}

	public static Object respondToFriendRequest(Request req, Response resp, Account self) throws SQLException{
		String username=req.params(":username");
		User user=UserStorage.getByUsername(username);
		if(user!=null){
			if(req.queryParams("accept")!=null){
				if(user instanceof ForeignUser){
					UserStorage.acceptFriendRequest(self.user.id, user.id, false);
					ActivityPubWorker.getInstance().sendFollowActivity(self.user, (ForeignUser) user);
				}else{
					UserStorage.acceptFriendRequest(self.user.id, user.id, true);
					Notification n=new Notification();
					n.type=Notification.Type.FRIEND_REQ_ACCEPT;
					n.actorID=self.user.id;
					NotificationsStorage.putNotification(user.id, n);
				}
			}else if(req.queryParams("decline")!=null){
				UserStorage.deleteFriendRequest(self.user.id, user.id);
				if(user instanceof ForeignUser){
					ActivityPubWorker.getInstance().sendRejectFriendRequestActivity(self.user, (ForeignUser) user);
				}
			}
			resp.redirect(Utils.back(req));
		}else{
			resp.status(404);
			return Utils.wrapError(req, resp, "user_not_found");
		}
		return "";
	}

	public static Object doRemoveFriend(Request req, Response resp, Account self) throws SQLException{
		String username=req.params(":username");
		User user=UserStorage.getByUsername(username);
		if(user!=null){
			FriendshipStatus status=UserStorage.getFriendshipStatus(self.user.id, user.id);
			if(status==FriendshipStatus.FRIENDS || status==FriendshipStatus.REQUEST_SENT || status==FriendshipStatus.FOLLOWING){
				UserStorage.unfriendUser(self.user.id, user.id);
				if(user instanceof ForeignUser){
					ActivityPubWorker.getInstance().sendUnfriendActivity(self.user, user);
				}
				if(isAjax(req)){
					resp.type("application/json");
					if("list".equals(req.queryParams("from")))
						return new WebDeltaResponseBuilder().remove("frow"+user.id).json();
					else
						return new WebDeltaResponseBuilder().refresh().json();
				}
				resp.redirect(Utils.back(req));
			}else{
				return Utils.wrapError(req, resp, "err_not_friends");
			}
		}else{
			resp.status(404);
			return Utils.wrapError(req, resp, "user_not_found");
		}
		return "";
	}

	public static Object follow(Request req, Response resp, Account self) throws SQLException{
		return "";
	}
}
