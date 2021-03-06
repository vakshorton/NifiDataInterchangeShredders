package com.hortonworks.nifi;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map.Entry;

import org.apache.commons.io.IOUtils;
import org.apache.nifi.annotation.behavior.SideEffectFree;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.InputStreamCallback;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.json.XML;

@SideEffectFree
@Tags({"JSON", "XML", "Parse"})
@CapabilityDescription("Shred deeply nested JSON payload into flattened attributes")
public class JsonXmlToAttributesShredder extends AbstractProcessor {
	private List<PropertyDescriptor> properties;
	private Set<Relationship> relationships;
	final Map<String,String> flattenedPaylod = new HashMap<String,String>();
	
	public static final String MATCH_ATTR = "match";

	static final PropertyDescriptor SHREDDER_TYPE = new PropertyDescriptor.Builder()
            .name("Shredder Type")
            .description("The expected format of the payload. Either JSON or XML")
            .required(true)
            .allowableValues("json", "xml")
            .defaultValue("json")
            .build();
	
	public static final Relationship REL_SUCCESS = new Relationship.Builder()
	        .name("SUCCESS")
	        .description("Succes relationship")
	        .build();
	
    public static final Relationship REL_FAIL = new Relationship.Builder()
            .name("FAIL")
            .description("FlowFiles are routed to this relationship when JSON cannot be parsed")
            .build();
	
	public void init(final ProcessorInitializationContext context){
	    List<PropertyDescriptor> properties = new ArrayList<>();
	    properties.add(SHREDDER_TYPE);
	    this.properties = Collections.unmodifiableList(properties);
		
	    Set<Relationship> relationships = new HashSet<Relationship>();
	    relationships.add(REL_SUCCESS);
	    relationships.add(REL_FAIL);
	    this.relationships = Collections.unmodifiableSet(relationships);
	}

	@Override
	public Set<Relationship> getRelationships(){
	    return relationships;
	}
	
	@Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return properties;
    }
	
	@Override
	public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
		//ProvenanceReporter provRep = session.getProvenanceReporter();

		FlowFile flowFile = session.get();
		if ( flowFile == null ) {
        	flowFile = session.create();
		}
		
		final ObjectMapper mapper = new ObjectMapper();
		final String shredderType = context.getProperty(SHREDDER_TYPE).getValue();
		final AtomicReference<JsonNode> rootNodeRef = new AtomicReference<>(null);
		try {
			session.read(flowFile, new InputStreamCallback() {
				@Override
				public void process(final InputStream in) throws IOException {
					try (final InputStream bufferedIn = new BufferedInputStream(in)) {
						if(shredderType.equalsIgnoreCase("json")){
							rootNodeRef.set(mapper.readTree(bufferedIn));
						}else{
							rootNodeRef.set(mapper.readTree(XML.toJSONObject(IOUtils.toString(bufferedIn)).toString()));
						}
					}
				}
			});
		} catch (final ProcessException pe) {
			getLogger().error("Failed to parse {} due to {}; routing to failure", new Object[] {flowFile, pe.toString()}, pe);
			session.transfer(flowFile, REL_FAIL);
			return;
		}

	    final JsonNode rootNode = rootNodeRef.get();
		List<String> fqnPath = new ArrayList<String>();
		Iterator<Entry<String, JsonNode>> jsonFieldsIterator = rootNode.getFields();
		while(jsonFieldsIterator.hasNext()){
			Entry<String, JsonNode> currentNodeEntry = jsonFieldsIterator.next(); 
			if(currentNodeEntry.getValue().isArray()){
				getLogger().debug("Current Field: " + currentNodeEntry.getKey() + " | Data Type: Array");
				fqnPath.add(currentNodeEntry.getKey());
				handleArray(currentNodeEntry.getValue(), fqnPath);
			}else if(currentNodeEntry.getValue().isObject()){
				getLogger().debug("Current Field: " + currentNodeEntry.getKey() + " | Data Type: Object");
				fqnPath.add(currentNodeEntry.getKey());
				handleObject(currentNodeEntry.getValue(), fqnPath);
			}else{
				if(currentNodeEntry.getValue().isNumber()){
					getLogger().debug("Current Field: " + getFQN(fqnPath, currentNodeEntry.getKey()) + " | " + currentNodeEntry.getValue().getNumberValue() + " | Data Type: Numeric Primitive");
					flattenedPaylod.put(getFQN(fqnPath, currentNodeEntry.getKey()), String.valueOf(currentNodeEntry.getValue().getNumberValue()));
				}else if(currentNodeEntry.getValue().isBoolean()){
					getLogger().debug("Current Field: " + getFQN(fqnPath, currentNodeEntry.getKey()) + " | " + currentNodeEntry.getValue().getBooleanValue() + " | Data Type: Booleen Primitive");
					flattenedPaylod.put(getFQN(fqnPath, currentNodeEntry.getKey()), String.valueOf(currentNodeEntry.getValue().getBooleanValue()));
				}else{
					getLogger().debug("Current Field: " + getFQN(fqnPath, currentNodeEntry.getKey()) + " | " + currentNodeEntry.getValue().getTextValue() + " | Data Type: String Primitive");
					flattenedPaylod.put(getFQN(fqnPath, currentNodeEntry.getKey()), currentNodeEntry.getValue().getTextValue());
				}
			}
		}
		
		flowFile = session.putAllAttributes(flowFile, flattenedPaylod);
		session.transfer(flowFile, REL_SUCCESS);
	}
			
	private ArrayList<Object> handleArray(JsonNode json, List<String> fqnPath){
		//System.out.println("Array Length: " + json.size());
		for(int i=0; i<json.size(); i++){
			fqnPath.add(String.valueOf(i));
			JsonNode currentElement = json.path(i); 
			Iterator<Entry<String, JsonNode>> jsonFieldsIterator = currentElement.getFields();
			while(jsonFieldsIterator.hasNext()){
				Entry<String, JsonNode> currentNodeEntry = jsonFieldsIterator.next(); 
				if(currentNodeEntry.getValue().isArray()){
					getLogger().debug("Current Field: " + getFQN(fqnPath, currentNodeEntry.getKey()) + " | Data Type: Array");
					fqnPath.add(currentNodeEntry.getKey());
					handleArray(currentNodeEntry.getValue(), fqnPath);
				}else if(currentNodeEntry.getValue().isObject()){
					getLogger().debug("Current Field: " + getFQN(fqnPath, currentNodeEntry.getKey()) + " | Data Type: Object");
					fqnPath.add(currentNodeEntry.getKey());
					handleObject(currentNodeEntry.getValue(), fqnPath);
				}else{
					if(currentNodeEntry.getValue().isNumber()){
						getLogger().debug("Current Field: " + getFQN(fqnPath, currentNodeEntry.getKey()) + " | " + currentNodeEntry.getValue().getNumberValue() + " | Data Type: Numeric Primitive");
						flattenedPaylod.put(getFQN(fqnPath, currentNodeEntry.getKey()), String.valueOf(currentNodeEntry.getValue().getNumberValue()));
					}else if(currentNodeEntry.getValue().isBoolean()){
						getLogger().debug("Current Field: " + getFQN(fqnPath, currentNodeEntry.getKey()) + " | " + currentNodeEntry.getValue().getBooleanValue() + " | Data Type: Booleen Primitive");
						flattenedPaylod.put(getFQN(fqnPath, currentNodeEntry.getKey()), String.valueOf(currentNodeEntry.getValue().getBooleanValue()));
					}else{
						getLogger().debug("Current Field: " + getFQN(fqnPath, currentNodeEntry.getKey()) + " | " + currentNodeEntry.getValue().getTextValue() + " | Data Type: String Primitive");
						flattenedPaylod.put(getFQN(fqnPath, currentNodeEntry.getKey()), currentNodeEntry.getValue().getTextValue());
					}
				}
			}
			fqnPath.remove(fqnPath.size()-1);
		}
		fqnPath.remove(fqnPath.size()-1);
		return null;
	}
			
	private Map<String, Object> handleObject(JsonNode json, List<String> fqnPath){
		//System.out.println("Array Length: " + json.size());
		Iterator<Entry<String, JsonNode>> jsonFieldsIterator = json.getFields();
		while(jsonFieldsIterator.hasNext()){
			Entry<String, JsonNode> currentNodeEntry = jsonFieldsIterator.next(); 
			if(currentNodeEntry.getValue().isArray()){
				getLogger().debug("Current Field: " + getFQN(fqnPath, currentNodeEntry.getKey()) + " | Data Type: Array");
				fqnPath.add(currentNodeEntry.getKey());
				handleArray(currentNodeEntry.getValue(), fqnPath);
			}else if(currentNodeEntry.getValue().isObject()){
				getLogger().debug("Current Field: " + getFQN(fqnPath, currentNodeEntry.getKey()) + " | Data Type: Object");
				fqnPath.add(currentNodeEntry.getKey());
				handleObject(currentNodeEntry.getValue(), fqnPath);
			}else{
				if(currentNodeEntry.getValue().isNumber()){
					getLogger().debug("Current Field: " + getFQN(fqnPath, currentNodeEntry.getKey()) + " | " + currentNodeEntry.getValue().getNumberValue() + " | Data Type: Numeric Primitive");
					flattenedPaylod.put(getFQN(fqnPath, currentNodeEntry.getKey()), String.valueOf(currentNodeEntry.getValue().getNumberValue()));
				}else if(currentNodeEntry.getValue().isBoolean()){
					getLogger().debug("Current Field: " + getFQN(fqnPath, currentNodeEntry.getKey()) + " | " + currentNodeEntry.getValue().getBooleanValue() + " | Data Type: Booleen Primitive");
					flattenedPaylod.put(getFQN(fqnPath, currentNodeEntry.getKey()), String.valueOf(currentNodeEntry.getValue().getBooleanValue()));
				}else{
					getLogger().debug("Current Field: " + getFQN(fqnPath, currentNodeEntry.getKey()) + " | " + currentNodeEntry.getValue().getTextValue() + " | Data Type: String Primitive");
					flattenedPaylod.put(getFQN(fqnPath, currentNodeEntry.getKey()), currentNodeEntry.getValue().getTextValue());
				}
			}					
		}
		fqnPath.remove(fqnPath.size()-1);
		return null;
	}
			
	private String getFQN(List<String> fqnPathList, String fieldName){
		String fqnString = "";
		if(fqnPathList.size()>0){
			String[] fqnPathArray = new String[fqnPathList.size()];
			fqnPathArray = fqnPathList.toArray(fqnPathArray);
			fqnString = String.join("_", fqnPathArray) + "_" + fieldName;
		}else{
			fqnString = fieldName;
		}
		return fqnString;
	}	   
}