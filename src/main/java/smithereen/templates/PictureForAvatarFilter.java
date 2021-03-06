package smithereen.templates;

import com.mitchellbosecke.pebble.error.PebbleException;
import com.mitchellbosecke.pebble.extension.Filter;
import com.mitchellbosecke.pebble.extension.escaper.SafeString;
import com.mitchellbosecke.pebble.template.EvaluationContext;
import com.mitchellbosecke.pebble.template.PebbleTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import smithereen.data.PhotoSize;
import smithereen.data.User;
import smithereen.storage.MediaStorageUtils;

public class PictureForAvatarFilter implements Filter{
	@Override
	public Object apply(Object input, Map<String, Object> args, PebbleTemplate self, EvaluationContext context, int lineNumber) throws PebbleException{
		List<PhotoSize> sizes;
		String additionalClasses="";
		if(input instanceof User){
			User user=(User) input;
			sizes=user.getAvatar();
			if(user.gender==User.Gender.FEMALE)
				additionalClasses=" female";
		}else{
			return "";
		}

		String _type=(String) args.get("type");
		PhotoSize.Type type, type2x;
		int size;
		boolean isRect=false;
		switch(_type){
			case "s":
				type=PhotoSize.Type.SMALL;
				type2x=PhotoSize.Type.MEDIUM;
				size=50;
				break;
			case "m":
				type=PhotoSize.Type.MEDIUM;
				type2x=PhotoSize.Type.LARGE;
				size=100;
				break;
			case "l":
				type=PhotoSize.Type.LARGE;
				type2x=PhotoSize.Type.XLARGE;
				size=200;
				break;
			case "xl":
				type2x=type=PhotoSize.Type.XLARGE;
				size=400;
				break;
			case "rl":
				type=PhotoSize.Type.RECT_LARGE;
				type2x=PhotoSize.Type.RECT_XLARGE;
				size=200;
				_type="l";
				isRect=true;
				break;
			case "rxl":
				type=type2x=PhotoSize.Type.RECT_XLARGE;
				size=400;
				_type="xl";
				isRect=true;
				break;
			default:
				throw new IllegalArgumentException("Wrong size type "+_type);
		}
		if(args.containsKey("size"))
			size=Templates.asInt(args.get("size"));
		if(sizes==null)
			return new SafeString("<span class=\"ava avaPlaceholder size"+_type.toUpperCase()+additionalClasses+"\" style=\"width: "+size+"px;height: "+size+"px\"></span>");

		PhotoSize jpeg1x=MediaStorageUtils.findBestPhotoSize(sizes, PhotoSize.Format.JPEG, type),
				jpeg2x=MediaStorageUtils.findBestPhotoSize(sizes, PhotoSize.Format.JPEG, type2x),
				webp1x=MediaStorageUtils.findBestPhotoSize(sizes, PhotoSize.Format.WEBP, type),
				webp2x=MediaStorageUtils.findBestPhotoSize(sizes, PhotoSize.Format.WEBP, type2x);

		int width=size, height=isRect ? jpeg1x.height : size;

		return new SafeString("<span class=\"ava avaHasImage size"+_type.toUpperCase()+"\"><picture>" +
				"<source srcset=\""+webp1x.src+", "+webp2x.src+" 2x\" type=\"image/webp\"/>" +
				"<source srcset=\""+jpeg1x.src+", "+jpeg2x.src+" 2x\" type=\"image/jpeg\"/>" +
				"<img src=\""+jpeg1x.src+"\" width=\""+width+"\" height=\""+height+"\" class=\"avaImage\"/>" +
				"</picture></span>");
	}

	@Override
	public List<String> getArgumentNames(){
		return Arrays.asList("type", "size");
	}
}
