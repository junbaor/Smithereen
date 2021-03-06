package smithereen.activitypub.handlers;

import java.sql.SQLException;

import smithereen.ObjectNotFoundException;
import smithereen.activitypub.ActivityHandlerContext;
import smithereen.activitypub.NestedActivityTypeHandler;
import smithereen.activitypub.objects.activities.Follow;
import smithereen.activitypub.objects.activities.Reject;
import smithereen.data.ForeignUser;
import smithereen.data.User;
import smithereen.storage.UserStorage;

public class RejectFollowPersonHandler extends NestedActivityTypeHandler<ForeignUser, Reject, Follow, User>{
	@Override
	public void handle(ActivityHandlerContext context, ForeignUser actor, Reject activity, Follow nested, User object) throws SQLException{
		User follower=UserStorage.getUserByActivityPubID(nested.actor.link);
		if(follower==null)
			throw new ObjectNotFoundException("Follower not found");
		UserStorage.unfriendUser(follower.id, actor.id);
	}
}
