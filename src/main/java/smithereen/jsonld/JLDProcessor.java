package smithereen.jsonld;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;

public class JLDProcessor{

	private static HashMap<String, JSONObject> schemaCache=new HashMap<>();
	private static final JSONObject inverseLocalContext;
	private static final JLDContext localContext;

	static{
		JSONObject lc=new JSONObject();
		lc.put("sc", JLD.SCHEMA_ORG);
		lc.put("sm", JLD.SMITHEREEN);
		lc.put("firstName", idAndTypeObject("sc:givenName", "sc:Text"));
		lc.put("lastName", idAndTypeObject("sc:familyName", "sc:Text"));
		lc.put("gender", idAndTypeObject("sc:gender", "sc:GenderType"));
		lc.put("birthDate", idAndTypeObject("sc:birthDate", "sc:Date"));
		lc.put("sensitive", "as:sensitive");
		lc.put("manuallyApprovesFollowers", "as:manuallyApprovesFollowers");
		lc.put("supportsFriendRequests", "sm:supportsFriendRequests");
		localContext=updateContext(new JLDContext(), new JSONArray(Arrays.asList(JLD.ACTIVITY_STREAMS, JLD.W3_SECURITY, lc)), new ArrayList<>(), null);
		inverseLocalContext=createReverseContext(localContext);
	}

	public static JSONArray expandToArray(Object src){
		return expandToArray(src, null);
	}

	public static JSONArray expandToArray(Object src, URI baseURI){
		Object jcontext=null;
		if(src instanceof JSONObject)
			jcontext=((JSONObject) src).opt("@context");
		JLDContext context=updateContext(new JLDContext(), jcontext, new ArrayList<String>(), baseURI);
		Object result=expand(context, null, src);
		if(result instanceof JSONArray)
			return (JSONArray) result;
		if(result==null)
			return new JSONArray();
		if(result instanceof JSONObject){
			JSONObject _r=(JSONObject) result;
			if(_r.length()==1 && _r.has("@graph"))
				return _r.getJSONArray("@graph");
		}
		return new JSONArray(Collections.singletonList(result));
	}

	public static JSONObject compact(JSONArray src, Object context){
		return compact(src, context, true);
	}

	public static JSONObject compact(JSONArray src, Object context, boolean compactArrays){
		JLDContext localContext=updateContext(new JLDContext(), context, new ArrayList<>(), null);
		JSONObject inverseContext=createReverseContext(localContext);
		Object _result=compact(localContext, inverseContext, null, src, compactArrays);
		JSONObject result;
		if(_result instanceof JSONObject)
			result=(JSONObject)_result;
		else
			result=new JSONObject();
		if(context instanceof JSONObject && !((JSONObject) context).isEmpty())
			result.put("@context", context);
		else if(!(context instanceof JSONObject))
			result.put("@context", context);
		return result;
	}

	public static JSONObject compactToLocalContext(JSONArray src){
		return (JSONObject)compact(localContext, inverseLocalContext, null, src, true);
	}

	public static JSONObject convertToLocalContext(JSONObject src){
		return compactToLocalContext(expandToArray(src));
	}

	private static JSONObject idAndTypeObject(String id, String type){
		JSONObject o=new JSONObject();
		o.put("@id", id);
		o.put("@type", type);
		return o;
	}

	private static String readResourceFile(String name){
		try{
			InputStream in=JLDProcessor.class.getResourceAsStream("/jsonld-schemas/"+name+".jsonld");
			byte[] buf=new byte[in.available()];
			in.read(buf);
			in.close();
			return new String(buf, StandardCharsets.UTF_8);
		}catch(IOException x){
			return null;
		}
	}

	private static JSONObject dereferenceContext(String iri) throws JSONException{
		if(iri.endsWith("/litepub-0.1.jsonld")){ // This avoids caching multiple copies of the same schema for different instances
			iri="https://example.com/schemas/litepub-0.1.jsonld";
		}
		if(schemaCache.containsKey(iri))
			return schemaCache.get(iri);
		String file=null;
		switch(iri){
			case "https://www.w3.org/ns/activitystreams":
				file=readResourceFile("activitystreams");
				break;
			case "https://w3id.org/security/v1":
				file=readResourceFile("w3-security");
				break;
			case "https://w3id.org/identity/v1":
				file=readResourceFile("w3-identity");
				break;
			case "https://example.com/schemas/litepub-0.1.jsonld":
				file=readResourceFile("litepub-0.1");
				break;
			default:
				System.out.println("Warning: can't dereference remote context '"+iri+"'");
				//throw new JLDException("loading remote context failed");
		}
		if(file!=null){
			JSONObject obj=new JSONObject(file);
			schemaCache.put(iri, obj);
			return obj;
		}
		return null;
	}

	private static JLDContext updateContext(JLDContext activeContext, Object _localContext, ArrayList<String> remoteContexts, URI baseURI) throws JSONException{
		JLDContext result=activeContext.clone();
		result.baseIRI=baseURI;
		result.originalBaseIRI=baseURI;
		if(_localContext==null){
			JLDContext r=new JLDContext();
			r.originalBaseIRI=baseURI;
			r.baseIRI=baseURI;
			return r;
		}

		ArrayList<Object> localContext=new ArrayList<>();
		if(_localContext instanceof JSONArray){
			JSONArray a=(JSONArray) _localContext;
			for(int i=0; i<a.length(); i++){
				localContext.add(a.isNull(i) ? null : a.get(i));
			}
		}else{
			localContext.add(_localContext);
		}

		for(Object context : localContext){
			if(context==null){
				result=new JLDContext();
				continue;
			}
			if(context instanceof String){
				String c=(String) context;
				if(!c.startsWith("http:/") && !c.startsWith("https:/")){
					throw new JLDException("relative context IRIs are not supported");
				}
				if(remoteContexts.contains(c)){
					throw new JLDException("recursive context inclusion");
				}
				remoteContexts.add(c);
				JSONObject deref=dereferenceContext(c);
				if(deref!=null){
					result=updateContext(result, deref.get("@context"), remoteContexts, baseURI);
				}else{
					System.err.println("Failed to dereference "+c);
				}

				continue;
			}
			if(!(context instanceof JSONObject)){
				throw new JLDException("invalid local context");
			}
			JSONObject c=(JSONObject) context;
			if(c.has("@base")){
				Object value=c.get("@base");
				if(value==JSONObject.NULL){
					result.baseIRI=null;
				}else if(value instanceof String){
					try{
						URI uri=new URI((String)value);
						if(uri.isAbsolute()){
							result.baseIRI=uri;
						}else if(result.baseIRI!=null){
							result.baseIRI=result.baseIRI.resolve(uri);
						}else{
							throw new JLDException("invalid base IRI");
						}
					}catch(URISyntaxException x){
						throw new JLDException("invalid base IRI", x);
					}
				}else{
					throw new JLDException("invalid base IRI");
				}
			}
			if(c.has("@vocab")){
				Object value=c.get("@vocab");
				if(value==JSONObject.NULL){
					result.vocabularyMapping=null;
				}else if(value instanceof String){
					String s=(String)value;
					if(s.contains(":")){
						result.vocabularyMapping=s;
					}else{
						throw new JLDException("invalid vocab mapping");
					}
				}else{
					throw new JLDException("invalid vocab mapping");
				}
			}
			if(c.has("@language")){
				Object value=c.get("@language");
				if(value==JSONObject.NULL || "und".equals(value)){
					result.defaultLanguage=null;
				}else if(value instanceof String){
					result.defaultLanguage=((String)value).toLowerCase();
				}else{
					throw new JLDException("invalid default language");
				}
			}

			for(Iterator<String> it=c.keys(); it.hasNext(); ){
				String k=it.next();
				if(k.equals("@base") || k.equals("@vocab") || k.equals("@language"))
					continue;
				createTermDefinition(result, c, k, new HashMap<>());
			}
		}

		return result;
	}

	private static void createTermDefinition(JLDContext activeContext, JSONObject localContext, String term, HashMap<String, Boolean> defined) throws JSONException{
		if(defined.containsKey(term)){
			if(defined.get(term))
				return;
			else
				throw new JLDException("cyclic IRI mapping");
		}
		defined.put(term, false);
		if(isKeyword(term))
			throw new JLDException("keyword redefinition");
		activeContext.termDefinitions.remove(term);
		Object value=localContext.get(term);
		if(value==JSONObject.NULL) value=null;
		if(value==null || (value instanceof JSONObject && ((JSONObject) value).has("@id") && ((JSONObject) value).isNull("@id"))){
			activeContext.termDefinitions.put(term, null);
			defined.put(term, true);
			return;
		}
		if(value instanceof String){
			JSONObject j=new JSONObject();
			j.put("@id", value);
			value=j;
		}
		if(!(value instanceof JSONObject)){
			throw new JLDException("invalid term definition");
		}
		JLDContext.TermDefinition definition=new JLDContext.TermDefinition();
		JSONObject v=(JSONObject)value;
		if(v.has("@type")){
			try{
				String type=v.getString("@type");
				type=expandIRI(activeContext, type, false, true, localContext, defined);
				if(!"@id".equals(type) && !"@vocab".equals(type)){
					URI uri=new URI(type);
					if(!uri.isAbsolute())
						throw new JLDException("invalid type mapping");
				}
				definition.typeMapping=type;
			}catch(JSONException|URISyntaxException x){
				throw new JLDException("invalid type mapping", x);
			}
		}
		if(v.has("@reverse")){
			if(v.has("@id"))
				throw new JLDException("invalid reverse property");
			try{
				String reverse=v.getString("@reverse");
				definition.iriMapping=expandIRI(activeContext, reverse, false, true, localContext, defined);
				if(!definition.iriMapping.contains(":"))
					throw new JLDException("invalid IRI mapping");
				if(v.has("@container")){
					definition.containerMapping=v.getString("@container");
					if(definition.containerMapping!=null && !definition.containerMapping.equals("@set") && !definition.containerMapping.equals("@index")){
						throw new JLDException("invalid reverse property");
					}
				}
				definition.reverseProperty=true;
				activeContext.termDefinitions.put(term, definition);
				defined.put(term, true);
			}catch(JSONException x){
				throw new JLDException("invalid reverse property");
			}
			return;
		}
		definition.reverseProperty=false;
		if(v.has("@id") && !term.equals(v.get("@id"))){
			try{
				definition.iriMapping=expandIRI(activeContext, v.getString("@id"), false, true, localContext, defined);
				if(!isKeyword(definition.iriMapping) && !definition.iriMapping.contains(":"))
					throw new JLDException("invalid IRI mapping");
				if("@context".equals(definition.iriMapping))
					throw new JLDException("invalid keyword mapping");
			}catch(JSONException x){
				throw new JLDException("invalid IRI mapping", x);
			}
		}else if(term.contains(":")){
			String[] sp=term.split(":", 2);
			String prefix=sp[0];
			String suffix=sp[1];
			if(localContext.has(prefix)){
				createTermDefinition(activeContext, localContext, prefix, defined);
			}
			if(activeContext.termDefinitions.containsKey(prefix))
				definition.iriMapping=activeContext.termDefinitions.get(prefix).iriMapping+suffix;
			else
				definition.iriMapping=term;
		}else if(activeContext.vocabularyMapping!=null){
			definition.iriMapping=activeContext.vocabularyMapping+term;
		}else{
			throw new JLDException("invalid IRI mapping");
		}

		if(v.has("@container")){
			String container=v.getString("@container");
			if(container!=null && !"@list".equals(container) && !"@set".equals(container) && !"@index".equals(container) && !"@language".equals(container))
				throw new JLDException("invalid container mapping");
			definition.containerMapping=container;
		}

		if(v.has("@language")){
			Object _language=v.get("@language");
			if(_language==JSONObject.NULL) _language=null;
			if(_language!=null && !(_language instanceof String))
				throw new JLDException("invalid language mapping");
			String language=(String)_language;
			if(language!=null){
				language=language.toLowerCase();
			}
			definition.languageMapping=language;
			definition.hasLanguageMapping=true;
		}

		activeContext.termDefinitions.put(term, definition);
		defined.put(term, true);
	}

	private static URI fixURI(URI uri){
		try{
			String path=uri.getPath().replace("../", "").replace("./", "");//.replaceAll("(?<!^)/\\./", "/");
			return new URI(uri.getScheme(), uri.getAuthority(), path, uri.getQuery(), uri.getFragment());
		}catch(URISyntaxException e){
			throw new IllegalArgumentException(e);
		}
	}

	private static String expandIRI(JLDContext activeContext, String value, boolean documentRelative, boolean vocab, JSONObject localContext, HashMap<String, Boolean> defined) throws JSONException{
		if(value==null || isKeyword(value))
			return value;
		if(localContext!=null && localContext.has(value) && (!defined.containsKey(value) || !defined.get(value))){
			createTermDefinition(activeContext, localContext, value, defined);
		}
		if(vocab && activeContext.termDefinitions.containsKey(value)){
			JLDContext.TermDefinition def=activeContext.termDefinitions.get(value);
			if(def!=null)
				return def.iriMapping;
			else
				return null;
		}
		if(value.contains(":") && !value.startsWith("#")){
			String[] sp=value.split(":", 2);
			String prefix=sp[0];
			String suffix=sp[1];
			if("_".equals(prefix) || suffix.startsWith("//")){
				return value;
			}
			if(localContext!=null && localContext.has(prefix) && (!defined.containsKey(prefix) || !defined.get(prefix))){
				createTermDefinition(activeContext, localContext, prefix, defined);
			}
			if(activeContext.termDefinitions.containsKey(prefix)){
				return activeContext.termDefinitions.get(prefix).iriMapping+suffix;
			}
			return value;
		}
		if(vocab && activeContext.vocabularyMapping!=null)
			return activeContext.vocabularyMapping+value;
		if(documentRelative && activeContext.baseIRI!=null){
			if(value.isEmpty())
				return activeContext.baseIRI.toString();
			if(URI.create(value).isAbsolute())
				return value;
			try{
				if(value.startsWith("?")){
					URI b=activeContext.baseIRI;
					return new URI(b.getScheme(), b.getAuthority(), b.getPath(), value.substring(1), null).toString();
				}
			}catch(URISyntaxException ignore){}
			if(value.startsWith("#"))
				return activeContext.baseIRI.resolve(value).toString();
			else if(value.startsWith("//"))
				return fixURI(URI.create(activeContext.baseIRI.getScheme()+":"+value).normalize()).toString();
			else
				return fixURI(activeContext.baseIRI.resolve(value)).toString(); // URI.resolve leaves /../ parts that go beyond root
		}
		return value;
	}

	private static boolean isKeyword(String key){
		switch(key){
			case "@context":
			case "@id":
			case "@value":
			case "@language":
			case "@type":
			case "@container":
			case "@list":
			case "@set":
			case "@reverse":
			case "@index":
			case "@base":
			case "@vocab":
			case "@graph":
				return true;
			default:
				return false;
		}
	}

	private static boolean isScalar(Object obj){
		return obj instanceof String || obj instanceof Integer || obj instanceof Boolean || obj instanceof Double;
	}

	private static boolean isJsonNativeType(Object obj){
		return obj instanceof Integer || obj instanceof Boolean || obj instanceof Double;
	}

	private static JSONObject jsonObjectWithSingleKey(String key, Object value) throws JSONException{
		JSONObject obj=new JSONObject();
		obj.put(key, value);
		return obj;
	}

	private static ArrayList<String> keysAsList(JSONObject obj) throws JSONException{
		ArrayList<String> keys=new ArrayList<>();
		Iterator<String> _keys=obj.keys();
		while(_keys.hasNext()){
			keys.add(_keys.next());
		}
		return keys;
	}

	private static Object expand(JLDContext activeContext, String activeProperty, Object element) throws JSONException{
		if(element==null || element==JSONObject.NULL)
			return null;
		if(isScalar(element)){
			if(activeProperty==null || "@graph".equals(activeProperty))
				return null;
			return expandValue(activeContext, activeProperty, element);
		}
		if(element instanceof JSONArray){
			JSONArray result=new JSONArray();
			JSONArray el=(JSONArray) element;
			for(int i=0;i<el.length();i++){
				Object item=el.get(i);
				Object expandedItem=expand(activeContext, activeProperty, item);
				if("@list".equals(activeProperty) || (activeContext.termDefinitions.containsKey(activeProperty) && "@list".equals(activeContext.termDefinitions.get(activeProperty).containerMapping))){
					if(expandedItem instanceof JSONArray || isListObject(expandedItem))
						throw new JLDException("list of lists");
				}
				if(expandedItem instanceof JSONArray){
					JSONArray xe=(JSONArray) expandedItem;
					for(int j=0;j<xe.length();j++){
						result.put(xe.get(j));
					}
				}else if(expandedItem!=null){
					result.put(expandedItem);
				}
			}
			return result;
		}
		if(!(element instanceof JSONObject))
			throw new JLDException("JSONObject expected here, found: "+element.getClass().getName());
		JSONObject el=(JSONObject)element;
		if(el.has("@context")){
			activeContext=updateContext(activeContext, el.isNull("@context") ? null : el.get("@context"), new ArrayList<>(), activeContext.originalBaseIRI);
		}
		JSONObject result=new JSONObject();
		ArrayList<String> keys=keysAsList(el);
		Collections.sort(keys);
		for(String key:keys){
			Object value=el.get(key);
			if(el.isNull(key))
				value=null;
			Object expandedValue=null;
			if("@context".equals(key))
				continue;

			String expandedProperty=expandIRI(activeContext, key, false, true, null, null);
			if(expandedProperty==null || (!expandedProperty.contains(":") && !isKeyword(expandedProperty)))
				continue;

			if(isKeyword(expandedProperty)){
				if("@reverse".equals(activeProperty))
					throw new JLDException("invalid reverse property map");
				if(result.has(expandedProperty))
					throw new JLDException("colliding keywords");
				if("@id".equals(expandedProperty)){
					if(!(value instanceof String))
						throw new JLDException("invalid @id value");
					expandedValue=expandIRI(activeContext, (String)value, true, false, null, null);
				}else if("@type".equals(expandedProperty)){
					if(value instanceof JSONArray){
						JSONArray expTypes=new JSONArray();
						for(int i=0;i<((JSONArray) value).length();i++){
							Object e=((JSONArray) value).get(i);
							if(!(e instanceof String)){
								throw new JLDException("invalid type value");
							}
							expTypes.put(expandIRI(activeContext, (String)e, true, true, null, null));
						}
						expandedValue=expTypes;
					}else if(value instanceof String){
						expandedValue=expandIRI(activeContext, (String)value, true, true, null, null);
					}else{
						throw new JLDException("invalid type value");
					}
				}else if("@graph".equals(expandedProperty)){
					expandedValue=expand(activeContext, "@graph", value);
				}else if("@value".equals(expandedProperty)){
					if(value!=null && !isScalar(value))
						throw new JLDException("invalid value object value");
					expandedValue=value;
					if(expandedValue==null){
						result.put("@value", JSONObject.NULL);
						continue;
					}
				}else if("@language".equals(expandedProperty)){
					if(!(value instanceof String))
						throw new JLDException("invalid language-tagged string");
					expandedValue=((String)value).toLowerCase();
				}else if("@index".equals(expandedProperty)){
					if(!(value instanceof String))
						throw new JLDException("invalid @index value");
					expandedValue=value;
				}else if("@list".equals(expandedProperty)){
					if(activeProperty==null || "@graph".equals(activeProperty))
						continue;
					expandedValue=expand(activeContext, activeProperty, value);
					if(isListObject(expandedValue) || (expandedValue instanceof JSONArray && ((JSONArray) expandedValue).length()>0 && isListObject(((JSONArray) expandedValue).get(0))))
						throw new JLDException("list of lists");
				}else if("@set".equals(expandedProperty)){
					expandedValue=expand(activeContext, activeProperty, value);
				}else if("@reverse".equals(expandedProperty)){
					if(!(value instanceof JSONObject))
						throw new JLDException("invalid @reverse value");
					expandedValue=expand(activeContext, "@reverse", value);
					if(expandedValue instanceof JSONObject){
						JSONObject jv=(JSONObject) expandedValue;
						if(jv.has("@reverse")){
							JSONObject rev=jv.getJSONObject("@reverse");
							for(String property:rev.keySet()){
								Object item=rev.get(property);
								if(!result.has(property))
									result.put(property, new JSONArray());
								// spec does not say this but tests do expect this, so...
								if(item instanceof JSONArray){
									JSONArray aitem=(JSONArray) item;
									for(int i=0;i<aitem.length();i++){
										((JSONArray) result.get(property)).put(aitem.get(i));
									}
								}else{
									((JSONArray) result.get(property)).put(item);
								}
							}
							jv.remove("@reverse");
						}
						if(jv.length()>0){
							if(!result.has("@reverse"))
								result.put("@reverse", new JSONObject());
							JSONObject reverseMap=result.getJSONObject("@reverse");
							for(String property:jv.keySet()){
								JSONArray items=jv.getJSONArray(property);
								for(int i=0;i<items.length();i++){
									Object item=items.get(i);
									if(isListObject(item) || isValueObject(item))
										throw new JLDException("invalid reverse property value");
									if(!reverseMap.has(property))
										reverseMap.put(property, new JSONArray());
									reverseMap.getJSONArray(property).put(item);
								}
							}
						}
					}
					continue;
				}

				if(expandedValue!=null){
					result.put(expandedProperty, expandedValue);
				}
				continue;
			}

			JLDContext.TermDefinition term=activeContext.termDefinitions.get(key);

			if(term!=null && "@language".equals(term.containerMapping) && value instanceof JSONObject){
				JSONArray exp=new JSONArray();
				JSONObject objval=(JSONObject)value;
				ArrayList<String> objkeys=keysAsList(objval);
				Collections.sort(objkeys);
				for(String lang:objkeys){
					Object langValue=objval.get(lang);
					if(!(langValue instanceof JSONArray)){
						langValue=new JSONArray(Collections.singletonList(langValue));
					}
					JSONArray langValueArr=(JSONArray) langValue;
					for(int j=0;j<langValueArr.length();j++){
						try{
							String item=langValueArr.getString(j);
							JSONObject r=jsonObjectWithSingleKey("@value", item);
							r.put("@language", lang.toLowerCase());
							exp.put(r);
						}catch(JSONException x){
							throw new JLDException("invalid language map value", x);
						}
					}
				}
				expandedValue=exp;
			}else if(term!=null && "@index".equals(term.containerMapping) && value instanceof JSONObject){
				JSONArray xv=new JSONArray();
				JSONObject v=(JSONObject)value;
				ArrayList<String> vkeys=keysAsList(v);
				Collections.sort(vkeys);
				for(String index:vkeys){
					Object indexValue=v.get(index);
					if(!(indexValue instanceof JSONArray))
						indexValue=new JSONArray(Collections.singletonList(indexValue));
					indexValue=expand(activeContext, key, indexValue);
					JSONArray ival=(JSONArray)indexValue;
					for(int j=0;j<ival.length();j++){
						JSONObject item=ival.getJSONObject(j);
						if(!item.has("@index"))
							item.put("@index", index);
						xv.put(item);
					}
				}
				expandedValue=xv;
			}else{
				expandedValue=expand(activeContext, key, value);
			}
			if(expandedValue==null)
				continue;
			if(term!=null && "@list".equals(term.containerMapping) && !isListObject(expandedValue)){
				if(!(expandedValue instanceof JSONArray))
					expandedValue=new JSONArray(Collections.singletonList(expandedValue));
				expandedValue=jsonObjectWithSingleKey("@list", expandedValue);
			}
			if(isListObject(expandedValue) && !(((JSONObject)expandedValue).get("@list") instanceof JSONArray)){
				expandedValue=jsonObjectWithSingleKey("@list", new JSONArray(Collections.singletonList(((JSONObject)expandedValue).get("@list"))));
			}
			if(term!=null && term.reverseProperty){
				if(!result.has("@reverse"))
					result.put("@reverse", new JSONObject());
				JSONObject reverseMap=result.getJSONObject("@reverse");
				if(!(expandedValue instanceof JSONArray))
					expandedValue=new JSONArray(Collections.singletonList(expandedValue));
				JSONArray xv=(JSONArray) expandedValue;
				for(int i=0;i<xv.length();i++){
					Object item=xv.get(i);
					if(isListObject(item) || isValueObject(item))
						throw new JLDException("invalid reverse property value");
					if(!reverseMap.has(expandedProperty))
						reverseMap.put(expandedProperty, new JSONArray());
					reverseMap.getJSONArray(expandedProperty).put(item);
				}
			}else{
				if(!result.has(expandedProperty))
					result.put(expandedProperty, new JSONArray());
				if(expandedValue instanceof JSONArray){
					JSONArray prop=result.getJSONArray(expandedProperty);
					for(int i=0;i<((JSONArray) expandedValue).length();i++){
						prop.put(((JSONArray) expandedValue).get(i));
					}
				}else{
					result.getJSONArray(expandedProperty).put(expandedValue);
				}
			}
		}

		if(result.has("@value")){
			for(String k:result.keySet()){
				if(!"@value".equals(k) && !"@language".equals(k) && !"@type".equals(k) && !"@index".equals(k))
					throw new JLDException("invalid value object");
			}
			if(result.has("@language") && result.has("@type"))
				throw new JLDException("invalid value object");
			if(result.isNull("@value")){
				result=null;
			}else if(!(result.get("@value") instanceof String) && result.has("@language")){
				throw new JLDException("invalid language-tagged value");
			}else if(result.has("@type")){
				try{
					new URI(result.getString("@type"));
				}catch(URISyntaxException x){
					throw new JLDException("invalid typed value", x);
				}
			}
		}else if(result.has("@type") && !(result.get("@type") instanceof JSONArray)){
			result.put("@type", new JSONArray(Collections.singletonList(result.get("@type"))));
		}else if(result.has("@set") || result.has("@list")){
			if(result.length()>2 || (result.length()>1 && !result.has("@index"))){
				throw new JLDException("invalid set or list object");
			}
			if(result.has("@set"))
				return result.get("@set");
		}

		if(result!=null && result.length()==1 && result.has("@language"))
			result=null;

		if(activeProperty==null || "@graph".equals(activeProperty)){
			if(result!=null && (result.length()==0 || result.has("@value") || result.has("@list")))
				result=null;
			else if(result!=null && result.length()==1 && result.has("@id"))
				result=null;
		}

		return result;
	}

	private static JSONObject expandValue(JLDContext activeContext, String activeProperty, Object value){
		JLDContext.TermDefinition term=activeContext.termDefinitions.get(activeProperty);
		if(term!=null && "@id".equals(term.typeMapping)){
			// the algorithm spec was clearly written without strongly-typed languages in mind. Sigh.
			if(value instanceof String)
				return jsonObjectWithSingleKey("@id", expandIRI(activeContext, (String)value, true, false, null, null));
			return jsonObjectWithSingleKey("@value", value);
		}
		if(term!=null && "@vocab".equals(term.typeMapping)){
			if(value instanceof String)
				return jsonObjectWithSingleKey("@id", expandIRI(activeContext, (String)value, true, true, null, null));
			return jsonObjectWithSingleKey("@value", value);
		}
		JSONObject result=jsonObjectWithSingleKey("@value", value);
		if(term!=null && term.typeMapping!=null)
			result.put("@type", term.typeMapping);
		else if(value instanceof String){
			if(term!=null && term.hasLanguageMapping){
				if(term.languageMapping!=null)
					result.put("@language", term.languageMapping);
			}else if(activeContext.defaultLanguage!=null){
				result.put("@language", activeContext.defaultLanguage);
			}
		}
		return result;
	}

	private static JSONObject createReverseContext(JLDContext activeContext) throws JSONException{
		JSONObject result=new JSONObject();
		String defaultLanguage="@none";
		ArrayList<String> keys=new ArrayList<>(activeContext.termDefinitions.keySet());
		Collections.sort(keys, new Comparator<String>(){
			@Override
			public int compare(String o1, String o2){
				if(o1.length()!=o2.length())
					return o1.length()-o2.length();
				return o1.compareTo(o2);
			}
		});
		for(String term:keys){
			JLDContext.TermDefinition termDefinition=activeContext.termDefinitions.get(term);
			if(termDefinition==null)
				continue;
			String container="@none";
			if(termDefinition.containerMapping!=null)
				container=termDefinition.containerMapping;
			String var=termDefinition.iriMapping;
			if(!result.has(var)){
				result.put(var, new JSONObject());
			}
			JSONObject containerMap=result.getJSONObject(var);
			if(!containerMap.has(container)){
				JSONObject o=new JSONObject();
				o.put("@language", new JSONObject());
				o.put("@type", new JSONObject());
				o.put("@any", term);
				containerMap.put(container, o);
			}
			JSONObject typeLanguageMap=containerMap.getJSONObject(container);
			if(termDefinition.reverseProperty){
				JSONObject typeMap=typeLanguageMap.getJSONObject("@type");
				if(!typeMap.has("@reverse"))
					typeMap.put("@reverse", term);
			}else if(termDefinition.typeMapping!=null){
				JSONObject typeMap=typeLanguageMap.getJSONObject("@type");
				if(!typeMap.has(termDefinition.typeMapping))
					typeMap.put(termDefinition.typeMapping, term);
			}else if(termDefinition.hasLanguageMapping){
				JSONObject languageMap=typeLanguageMap.getJSONObject("@language");
				String language=termDefinition.languageMapping==null ? "@null" : termDefinition.languageMapping;
				if(!languageMap.has(language))
					languageMap.put(language, term);
			}else{
				JSONObject languageMap=typeLanguageMap.getJSONObject("@language");
				if(!languageMap.has(defaultLanguage))
					languageMap.put(defaultLanguage, term);
				if(!languageMap.has("@none"))
					languageMap.put("@none", term);
				JSONObject typeMap=typeLanguageMap.getJSONObject("@type");
				if(!typeMap.has("@none"))
					typeMap.put("@none", term);
			}
		}
		return result;
	}

	private static boolean isListObject(Object o){
		return o instanceof JSONObject && ((JSONObject) o).has("@list");
	}

	private static boolean isValueObject(Object o){
		return o instanceof JSONObject && ((JSONObject) o).has("@value");
	}

	private static boolean isNodeObject(JSONObject o){
		return !o.has("@value") && !o.has("@list") && !o.has("@set");
	}

	private static String selectTerm(JSONObject inverseContext, String iri, ArrayList<String> containers, String typeLanguage, ArrayList<String> preferredValues){
		JSONObject containerMap=inverseContext.getJSONObject(iri);
		for(String container:containers){
			if(!containerMap.has(container))
				continue;
			JSONObject typeLanguageMap=containerMap.getJSONObject(container);
			JSONObject valueMap=typeLanguageMap.getJSONObject(typeLanguage);
			for(String item:preferredValues){
				if(!valueMap.has(item))
					continue;
				return valueMap.getString(item);
			}
		}
		return null;
	}

	private static String compactIRI(JLDContext activeContext, JSONObject inverseContext, String iri, Object value, boolean vocab, boolean reverse){
		if(iri==null)
			return null;
		JSONObject vj=value instanceof JSONObject ? (JSONObject)value : null;
		if(vocab && inverseContext.has(iri)){
			String defaultLanguage=activeContext.defaultLanguage!=null ? activeContext.defaultLanguage : "@none";
			ArrayList<String> containers=new ArrayList<>();
			String typeLanguage="@language";
			String typeLanguageValue="@null";
			if(value instanceof JSONObject && ((JSONObject) value).has("@index")){
				containers.add("@index");
			}
			if(reverse){
				typeLanguageValue="@reverse";
				containers.add("@set");
			}else if(isListObject(value)){
				if(!vj.has("@index")){
					containers.add("@list");
				}
				JSONArray list=vj.getJSONArray("@list");
				String commonType=null, commonLanguage=null;
				if(list.isEmpty()){
					commonLanguage=defaultLanguage;
				}
				for(int i=0;i<list.length();i++){
					JSONObject item=list.getJSONObject(i);
					String itemLanguage="@none", itemType="@none";
					if(item.has("@value")){
						if(item.has("@language"))
							itemLanguage=item.getString("@language");
						else if(item.has("@type"))
							itemType=item.getString("@type");
						else
							itemLanguage="@null";
					}else{
						itemType="@id";
					}
					if(commonLanguage==null)
						commonLanguage=itemLanguage;
					else if(!itemLanguage.equals(commonLanguage) && item.has("@value"))
						commonLanguage="@none";
					if(commonType==null)
						commonType=itemType;
					else if(!itemType.equals(commonType))
						commonType="@none";
					if(commonLanguage.equals("@none") && commonType.equals("@none"))
						break;
				}
				if(commonLanguage==null)
					commonLanguage="@none";
				if(commonType==null)
					commonType="@none";
				if(!commonType.equals("@none")){
					typeLanguage="@type";
					typeLanguageValue=commonType;
				}else{
					typeLanguageValue=commonLanguage;
				}
			}else{
				if(isValueObject(value)){
					if(vj.has("@language") && !vj.has("@index")){
						typeLanguageValue=vj.getString("@language");
						containers.add("@language");
					}else if(vj.has("@type")){
						typeLanguageValue=vj.getString("@type");
						typeLanguage="@type";
					}
				}else{
					typeLanguage="@type";
					typeLanguageValue="@id";
				}
				containers.add("@set");
			}
			containers.add("@none");
			if(typeLanguageValue==null)
				typeLanguageValue="@null";
			ArrayList<String> preferredValues=new ArrayList<>();
			if("@reverse".equals(typeLanguageValue))
				preferredValues.add("@reverse");
			if(("@id".equals(typeLanguageValue) || "@reverse".equals(typeLanguageValue)) && vj!=null && vj.has("@id")){
				String r=compactIRI(activeContext, inverseContext, vj.getString("@id"), null, true, false);
				JLDContext.TermDefinition td=activeContext.termDefinitions.get(r);
				if(td!=null && vj.getString("@id").equals(td.iriMapping)){
					preferredValues.add("@vocab");
					preferredValues.add("@id");
					preferredValues.add("@none");
				}else{
					preferredValues.add("@id");
					preferredValues.add("@vocab");
					preferredValues.add("@none");
				}
			}else{
				preferredValues.add(typeLanguageValue);
				preferredValues.add("@none");
			}
			String term=selectTerm(inverseContext, iri, containers, typeLanguage, preferredValues);
			if(term!=null)
				return term;
		}
		if(vocab && activeContext.vocabularyMapping!=null){
			if(iri.startsWith(activeContext.vocabularyMapping) && iri.length()>activeContext.vocabularyMapping.length()){
				String suffix=iri.substring(activeContext.vocabularyMapping.length());
				if(!activeContext.termDefinitions.containsKey(suffix))
					return suffix;
			}
		}
		String compactIRI=null;
		for(String term:activeContext.termDefinitions.keySet()){
			if(term.contains(":"))
				continue;
			JLDContext.TermDefinition termDefinition=activeContext.termDefinitions.get(term);
			if(termDefinition==null || iri.equals(termDefinition.iriMapping) || !iri.startsWith(termDefinition.iriMapping))
				continue;
			String candidate=term+":"+iri.substring(termDefinition.iriMapping.length());
			if((compactIRI==null || candidate.length()<compactIRI.length() || candidate.compareTo(compactIRI)<0) && (!activeContext.termDefinitions.containsKey(candidate) || (value==null && iri.equals(activeContext.termDefinitions.get(candidate).iriMapping)))){
				compactIRI=candidate;
			}
		}
		if(compactIRI!=null)
			return compactIRI;
		// If vocab is false then transform iri to a relative IRI using the document's base IRI.
		// we don't know document IRIs
		return iri;
	}

	private static Object compactValue(JLDContext activeContext, JSONObject inverseContext, String activeProperty, JSONObject value){
		int numberMembers=value.length();
		JLDContext.TermDefinition term=activeContext.termDefinitions.get(activeProperty);
		if(value.has("@index") && term!=null && "@index".equals(term.containerMapping))
			numberMembers--;
		if(numberMembers>2)
			return value;
		if(value.has("@id")){
			if(numberMembers==1 && term!=null && "@id".equals(term.typeMapping)){
				return compactIRI(activeContext, inverseContext, value.getString("@id"), null, false, false);
			}else if(numberMembers==1 && term!=null && "@vocab".equals(term.typeMapping)){
				return compactIRI(activeContext, inverseContext, value.getString("@id"), null, true, false);
			}
			return value;
		}else if(value.has("@type") && term!=null && value.getString("@type").equals(term.typeMapping)){
			return value.get("@value");
		}else if(value.has("@language") && term!=null && value.getString("@language").equals(term.languageMapping)){
			return value.get("@value");
		}else if(numberMembers==1 && (!(value.get("@value") instanceof String) || activeContext.defaultLanguage==null || (term==null || !term.hasLanguageMapping))){
			return value.get("@value");
		}
		return value;
	}

	private static Object compact(JLDContext activeContext, JSONObject inverseContext, String activeProperty, Object element, boolean compactArrays){
		if(isScalar(element))
			return element;
		JLDContext.TermDefinition term=activeContext.termDefinitions.get(activeProperty);
		if(element instanceof JSONArray){
			JSONArray e=(JSONArray) element;
			JSONArray result=new JSONArray();
			for(int i=0;i<e.length();i++){
				Object item=e.get(i);
				Object compactedElement=compact(activeContext, inverseContext, activeProperty, item, compactArrays);
				if(compactedElement!=null)
					result.put(compactedElement);
			}
			if(result.length()==1 && (term==null || term.containerMapping==null) && compactArrays)
				return result.get(0);
			return result;
		}
		JSONObject e=(JSONObject) element;
		if(e.has("@value") || e.has("@id")){
			Object res=compactValue(activeContext, inverseContext, activeProperty, e);
			if(isScalar(res))
				return res;
		}
		boolean insideReverse="@reverse".equals(activeProperty);
		JSONObject result=new JSONObject();
		ArrayList<String> keys=keysAsList(e);
		Collections.sort(keys);
		for(String expandedProperty:keys){
			Object expandedValue=e.get(expandedProperty);
			if(expandedValue==JSONObject.NULL)
				continue;
			Object compactedValue;
			if(expandedProperty.equals("@id") || expandedProperty.equals("@type")){
				if(expandedValue instanceof String){
					compactedValue=compactIRI(activeContext, inverseContext, (String)expandedValue, null, expandedProperty.equals("@type"), false);
				}else{
					JSONArray _compactedValue=new JSONArray();
					JSONArray _expandedValue=(JSONArray)expandedValue;
					for(int i=0;i<_expandedValue.length();i++){
						_compactedValue.put(compactIRI(activeContext, inverseContext, _expandedValue.getString(i), null, true, false));
					}
					if(_compactedValue.length()==1)
						compactedValue=_compactedValue.get(0);
					else
						compactedValue=_compactedValue;
				}
				String alias=compactIRI(activeContext, inverseContext, expandedProperty, null, true, false);
				result.put(alias, compactedValue);
				continue;
			}
			if(expandedProperty.equals("@reverse")){
				JSONObject _compactedValue=(JSONObject) compact(activeContext, inverseContext, "@reverse", expandedValue, compactArrays);
				compactedValue=_compactedValue;
				ArrayList<String> keys2=keysAsList(_compactedValue);
				for(String property:keys2){
					Object value=_compactedValue.get(property);
					JLDContext.TermDefinition pterm=activeContext.termDefinitions.get(property);
					if(pterm!=null && pterm.reverseProperty){
						if(("@set".equals(pterm.containerMapping) || !compactArrays) && !(value instanceof JSONArray))
							value=new JSONArray(Collections.singletonList(value));
						if(!result.has(property)){
							result.put(property, value);
						}else{
							Object val=result.get(property);
							JSONArray valArr;
							if(val instanceof JSONArray){
								valArr=(JSONArray) val;
							}else{
								valArr=new JSONArray(Collections.singletonList(val));
								result.put(property, valArr);
							}
							if(value instanceof JSONArray){
								for(int i=0;i<((JSONArray) value).length();i++){
									valArr.put(((JSONArray) value).get(i));
								}
							}else{
								valArr.put(value);
							}
						}
						_compactedValue.remove(property);
					}
				}
				if(!_compactedValue.isEmpty()){
					String alias=compactIRI(activeContext, inverseContext, "@reverse", null, true, false);
					result.put(alias, _compactedValue);
				}
				continue;
			}
			if(expandedProperty.equals("@index") && term!=null && "@index".equals(term.containerMapping))
				continue;
			if(expandedProperty.equals("@index") || expandedProperty.equals("@value") || expandedProperty.equals("@language")){
				String alias=compactIRI(activeContext, inverseContext, expandedProperty, null, true, false);
				result.put(alias, expandedValue);
				continue;
			}
			if(expandedValue instanceof JSONArray && ((JSONArray) expandedValue).isEmpty()){
				String itemActiveProperty=compactIRI(activeContext, inverseContext, expandedProperty, expandedValue, true, insideReverse);
				if(!result.has(itemActiveProperty)){
					result.put(itemActiveProperty, Collections.EMPTY_LIST);
				}else if(!(result.get(itemActiveProperty) instanceof JSONArray)){
					result.put(itemActiveProperty, Collections.singletonList(result.getString(itemActiveProperty)));
				}
			}
			JSONArray ev=(JSONArray)expandedValue;
			for(int i=0;i<ev.length();i++){
				Object expandedItem=ev.get(i);
				String itemActiveProperty=compactIRI(activeContext, inverseContext, expandedProperty, expandedItem, true, insideReverse);
				String container=null;
				JLDContext.TermDefinition activeTerm=activeContext.termDefinitions.get(itemActiveProperty);
				if(activeTerm!=null && activeTerm.containerMapping!=null){
					container=activeTerm.containerMapping;
				}
				Object compactedItem=compact(activeContext, inverseContext, itemActiveProperty, expandedItem instanceof JSONObject && ((JSONObject)expandedItem).has("@list") ? ((JSONObject)expandedItem).getJSONArray("@list") : expandedItem, compactArrays);
				if(isListObject(expandedItem)){
					if(!(compactedItem instanceof JSONArray)){
						compactedItem=new JSONArray(Collections.singletonList(compactedItem));
					}
					JSONObject _expandedItem=(JSONObject)expandedItem;
					if(!"@list".equals(container)){
						compactedItem=jsonObjectWithSingleKey(compactIRI(activeContext, inverseContext, "@list", compactedItem, false, false), compactedItem);
						if(_expandedItem.has("@index")){
							((JSONObject)compactedItem).put(compactIRI(activeContext, inverseContext, "@index", _expandedItem.get("@index"), false, false), _expandedItem.get("@index"));
						}
					}else{
						throw new JLDException("compaction to list of lists");
					}
				}
				if("@language".equals(container) || "@index".equals(container)){
					if(!result.has(itemActiveProperty))
						result.put(itemActiveProperty, new JSONObject());
					JSONObject mapObject=result.getJSONObject(itemActiveProperty);
					if("@language".equals(container) && compactedItem instanceof JSONObject && ((JSONObject) compactedItem).has("@value")){
						compactedItem=((JSONObject) compactedItem).get("@value");
						JSONObject _expandedItem=(JSONObject)expandedItem;
						String mapKey=_expandedItem.getString(container);
						if(!mapObject.has(mapKey)){
							mapObject.put(mapKey, compactedItem);
						}else{
							Object o=mapObject.get(mapKey);
							if(o instanceof JSONArray)
								((JSONArray) o).put(compactedItem);
							else
								mapObject.put(mapKey, new JSONArray(Arrays.asList(mapObject.get(mapKey), compactedItem)));
						}
					}
				}else{
					if(!compactArrays && ("@list".equals(container) || "@set".equals(container) || "@list".equals(expandedProperty) || "@graph".equals(expandedProperty)) && !(compactedItem instanceof JSONArray)){
						compactedItem=new JSONArray(Collections.singletonList(compactedItem));
					}
					if(!result.has(itemActiveProperty)){
						result.put(itemActiveProperty, compactedItem);
					}else{
						Object o=result.get(itemActiveProperty);
						if(o instanceof JSONArray)
							((JSONArray) o).put(compactedItem);
						else
							result.put(itemActiveProperty, new JSONArray(Arrays.asList(result.get(itemActiveProperty), compactedItem)));
					}
				}
			}
		}
		return result;
	}

	private static boolean jsonArrayContains(JSONArray array, Object what){
		for(Object o:array){
			if(o.getClass().isInstance(what)){
				if(o.toString().equals(what.toString()))
					return true;
			}
		}
		return false;
	}

	private static void generateNodeMap(Object element, JSONObject nodeMap, String activeGraph, /*String or JSONObject*/Object activeSubject, String activeProperty, JSONObject list, BlankNodeIdentifierGenerator idGen){
		if(element instanceof JSONArray){
			JSONArray array=(JSONArray) element;
			for(int i=0;i<array.length();i++){
				generateNodeMap(array.get(i), nodeMap, activeGraph, activeSubject, activeProperty, list, idGen);
			}
			return;
		}
		JSONObject el=(JSONObject) element;
		if(!nodeMap.has(activeGraph))
			nodeMap.put(activeGraph, new JSONObject());
		JSONObject graph=nodeMap.getJSONObject(activeGraph);
		JSONObject node=null;
		if(activeSubject==null){
			node=null;
		}else if(activeSubject instanceof String){
			String _activeSubject=(String)activeSubject;
			if(!graph.has(_activeSubject))
				graph.put(_activeSubject, new JSONObject());
			node=graph.getJSONObject(_activeSubject);
		}
		if(el.has("@type")){
			JSONArray type=el.optJSONArray("@type");
			if(type!=null){
				for(int i=0; i<type.length(); i++){
					String item=type.getString(i);
					if(item.startsWith("_:")){
						item=idGen.generate(item);
						type.put(i, item);
					}
				}
			}else{
				String item=el.getString("@type");
				if(item.startsWith("_:")){
					item=idGen.generate(item);
					el.put("@type", item);
				}
			}
		}
		if(el.has("@value")){
			if(list==null){
				if(!node.has(activeProperty))
					node.put(activeProperty, new JSONArray(Collections.singletonList(el)));
				else if(!jsonArrayContains(node.getJSONArray(activeProperty), el))
					node.getJSONArray(activeProperty).put(el);
			}else{
				list.getJSONArray("@list").put(el);
			}
		}else if(el.has("@list")){
			JSONObject result=new JSONObject();
			result.put("@list", new JSONArray());
			generateNodeMap(el.getJSONArray("@list"), nodeMap, activeGraph, activeSubject, activeProperty, result, idGen);
			node.getJSONArray(activeProperty).put(result);
		}else{
			String id;
			if(el.has("@id")){
				String _id=el.getString("@id");
				el.remove("@id");
				if(_id.startsWith("_:"))
					id=idGen.generate(_id);
				else
					id=_id;
			}else{
				id=idGen.generate(null);
			}
			if(!graph.has(id)){
				graph.put(id, jsonObjectWithSingleKey("@id", id));
			}
			if(activeSubject instanceof JSONObject){
				node=graph.getJSONObject(id);
				if(!node.has(activeProperty)){
					node.put(activeProperty, new JSONArray(Collections.singletonList(activeSubject)));
				}else{
					JSONArray ap=node.getJSONArray(activeProperty);
					if(!jsonArrayContains(ap, activeSubject))
						ap.put(activeSubject);
				}
			}else if(activeProperty!=null){
				JSONObject reference=new JSONObject();
				reference.put("@id", id);
				if(list==null){
					if(!node.has(activeProperty)){
						node.put(activeProperty, new JSONArray(Collections.singletonList(reference)));
					}else{
						JSONArray ap=node.getJSONArray(activeProperty);
						if(!jsonArrayContains(ap, reference))
							ap.put(reference);
					}
				}else{
					list.getJSONArray("@list").put(jsonObjectWithSingleKey("@id", id));
				}
			}
			node=graph.getJSONObject(id);
			if(el.has("@type")){
				JSONArray type=el.getJSONArray("@type");
				if(!node.has("@type"))
					node.put("@type", new JSONArray());
				JSONArray nodeType=node.getJSONArray("@type");
				for(Object _item:type){
					String item=(String)_item;
					if(!jsonArrayContains(nodeType, item))
						nodeType.put(item);
				}
				el.remove("@type");
			}
			if(el.has("@index")){
				if(node.has("@index"))
					throw new JLDException("conflicting indexes");
				node.put("@index", el.remove("@index"));
			}
			if(el.has("@reverse")){
				JSONObject referencedNode=new JSONObject();
				referencedNode.put("@id", id);
				JSONObject reverseMap=el.getJSONObject("@reverse");
				for(String property:reverseMap.keySet()){
					JSONArray values=reverseMap.getJSONArray(property);
					for(Object value:values){
						generateNodeMap(value, nodeMap, activeGraph, referencedNode, property, null, idGen);
					}
				}
				el.remove("@reverse");
			}
			if(el.has("@graph")){
				generateNodeMap(el.get("@graph"), nodeMap, id, null, null, null, idGen);
				el.remove("@graph");
			}
			ArrayList<String> keys=keysAsList(el);
			Collections.sort(keys);
			for(String property:keys){
				Object value=el.get(property);
				if(property.startsWith("_:"))
					property=idGen.generate(property);
				if(!node.has(property))
					node.put(property, new JSONArray());
				generateNodeMap(value, nodeMap, activeGraph, id, property, null, idGen);
			}
		}
	}

	private static Object objectToRDF(JSONObject item){
		if(isNodeObject(item)){
			String _id=item.getString("@id");
			if(_id.startsWith("_:"))
				return _id;
			URI id=URI.create(_id);
			if(!id.isAbsolute())
				return null;
			return id;
		}
		Object value=item.get("@value");
		String datatype=item.optString("@type", null);
		if(value instanceof Boolean){
			value=value.toString();
			if(datatype==null)
				datatype=RDF.NS_XSD+"boolean";
		}else if((value instanceof Double && (double)value%1.0!=0.0) || (value instanceof Integer && (RDF.NS_XSD+"double").equals(datatype))){
			double d;
			if(value instanceof Integer){
				d=(int)(Integer)value;
			}else{
				d=(double)value;
			}
			value=String.format(Locale.US, "%.15E", d).replaceAll("(\\d)0*E\\+?(-?)0+(\\d+)","$1E$2$3");
			if(datatype==null)
				datatype=RDF.NS_XSD+"double";
		}else if(value instanceof Integer || value instanceof Double){
			if(value instanceof Integer)
				value=value.toString();
			else
				value=String.valueOf((int)(double)value);
			if(datatype==null)
				datatype=RDF.NS_XSD+"integer";
		}else if(datatype==null){
			if(item.has("@language"))
				datatype=RDF.NS_RDF+"langString";
			else
				datatype=RDF.NS_RDF+"string";
		}
		return new RDFLiteral((String)value, URI.create(datatype), item.optString("@language", null));
	}

	private static Object listToRDF(JSONArray list, ArrayList<RDFTriple> triples, BlankNodeIdentifierGenerator idGen){
		if(list.isEmpty())
			return URI.create(RDF.NS_RDF+"nil");
		ArrayList<String> bnodes=new ArrayList<>();
		for(int i=0;i<list.length();i++)
			bnodes.add(idGen.generate(null));
		for(int i=0;i<list.length();i++){
			String subject=bnodes.get(i);
			JSONObject item=list.getJSONObject(i);
			Object object=objectToRDF(item);
			if(object!=null){
				triples.add(new RDFTriple(subject, URI.create(RDF.NS_RDF+"first"), object));
			}
			Object rest=i<bnodes.size()-1 ? bnodes.get(i+1) : URI.create(RDF.NS_RDF+"nil");
			triples.add(new RDFTriple(subject, URI.create(RDF.NS_RDF+"rest"), rest));
		}
		return bnodes.isEmpty() ? RDF.NS_RDF+"nil" : bnodes.get(0);
	}

	public static ArrayList<RDFTriple> toRDF(Object input, URI baseURI){
		final Comparator<String> iriComparator=new Comparator<String>(){
			@Override
			public int compare(String o1, String o2){
				if(o1.startsWith("@"))
					o1='<'+RDF.NS_RDF+o1.substring(1)+'>';
				else if(!o1.startsWith("_:"))
					o1='<'+o1+'>';
				if(o2.startsWith("@"))
					o2='<'+RDF.NS_RDF+o2.substring(1)+'>';
				else if(!o2.startsWith("_:"))
					o2='<'+o2+'>';
				return o1.compareTo(o2);
			}
		};
		boolean produceGeneralizedRDF=false;
		ArrayList<RDFTriple> allTriples=new ArrayList<>();

		JSONArray expanded=expandToArray(input, baseURI);
		JSONObject nodeMap=new JSONObject();
		nodeMap.put("@default", new JSONObject());
		BlankNodeIdentifierGenerator idGen=new BlankNodeIdentifierGenerator();
		generateNodeMap(expanded, nodeMap, "@default", null, null, null, idGen);
		ArrayList<String> nodeMapKeys=keysAsList(nodeMap);
		Collections.sort(nodeMapKeys, iriComparator);
		for(String graphName:nodeMapKeys){
			if(graphName.charAt(0)!='@' && graphName.charAt(0)!='_'){
				if(!URI.create(graphName).isAbsolute())
					continue;
			}
			JSONObject graph=nodeMap.getJSONObject(graphName);
			ArrayList<RDFTriple> triples=new ArrayList<>();
			ArrayList<String> graphKeys=keysAsList(graph);
			Collections.sort(graphKeys, iriComparator);
			for(String subject:graphKeys){
				URI subjectURI=null;
				if(subject.charAt(0)!='@' && subject.charAt(0)!='_'){
					subjectURI=URI.create(subject);
					if(!subjectURI.isAbsolute())
						continue;
				}
				JSONObject node=graph.getJSONObject(subject);
				ArrayList<String> nodeKeys=keysAsList(node);
				Collections.sort(nodeKeys, iriComparator);
				for(String property:nodeKeys){
					if(property.equals("@id"))
						continue;
					Iterable<Object> values=node.optJSONArray(property);
					if(values==null){
						values=Collections.singletonList(node.get(property));
					}
					if(property.equals("@type")){
						for(Object _type:values){
							String type=(String)_type;
							triples.add(new RDFTriple(subjectURI==null ? subject : subjectURI, URI.create(RDF.NS_RDF+"type"), type.charAt(0)=='_' ? type : URI.create(type)));
						}
					}else if(isKeyword(property)){
						continue;
					}else if(property.startsWith("_:b") && !produceGeneralizedRDF){
						continue;
					}else if(!URI.create(property).isAbsolute()){
						continue;
					}else{
						for(Object _item:values){
							JSONObject item=(JSONObject)_item;
							if(isListObject(item)){
								ArrayList<RDFTriple> listTriples=new ArrayList<>();
								Object listHead=listToRDF(item.getJSONArray("@list"), listTriples, idGen);
								triples.add(new RDFTriple(subjectURI==null ? subject : subjectURI, URI.create(property), listHead));
								triples.addAll(listTriples);
							}else{
								Object result=objectToRDF(item);
								if(result!=null){
									triples.add(new RDFTriple(subjectURI==null ? subject : subjectURI, URI.create(property), result));
								}
							}
						}
					}
				}
			}
			if(!"@default".equals(graphName)){
				if(graphName.startsWith("_:")){
					for(RDFTriple triple : triples){
						triple.graphName=graphName;
					}
				}else{
					URI graphNameURI=URI.create(graphName);
					for(RDFTriple triple : triples){
						triple.graphName=graphNameURI;
					}
				}
			}
			allTriples.addAll(triples);
		}
		return allTriples;
	}

	public static JSONArray flatten(Object element, URI baseURI){
		JSONObject nodeMap=new JSONObject();
		nodeMap.put("@default", new JSONObject());
		BlankNodeIdentifierGenerator idGen=new BlankNodeIdentifierGenerator();
		JSONArray expanded=expandToArray(element, baseURI);
		System.out.println(expanded.toString(4));
		generateNodeMap(expanded, nodeMap, "@default", null, null, null, idGen);
		//generateNodeMap(element, nodeMap, "@default", null, null, null, idGen);
		System.out.println(nodeMap.toString(4));
		JSONObject defaultGraph=nodeMap.getJSONObject("@default");
		for(String graphName:nodeMap.keySet()){
			if("@default".equals(graphName))
				continue;
			if(!defaultGraph.has(graphName))
				defaultGraph.put(graphName, jsonObjectWithSingleKey("@id", graphName));
			JSONObject entry=defaultGraph.getJSONObject(graphName);
			entry.put("@graph", new JSONArray());
			JSONObject graph=nodeMap.getJSONObject(graphName);
			ArrayList<String> graphKeys=keysAsList(graph);
			Collections.sort(graphKeys);
			for(String id:graphKeys){
				JSONObject node=graph.getJSONObject(id);
				if(!(node.has("@id") && node.length()==1))
					entry.getJSONArray("@graph").put(node);
			}
		}
		JSONArray flattened=new JSONArray();
		ArrayList<String> defaultGraphKeys=keysAsList(defaultGraph);
		Collections.sort(defaultGraphKeys);
		for(String id:defaultGraphKeys){
			JSONObject node=defaultGraph.getJSONObject(id);
			if(!(node.has("@id") && node.length()==1))
				flattened.put(node);
		}
		System.out.println(flattened.toString(4));
		return flattened;
	}
}
