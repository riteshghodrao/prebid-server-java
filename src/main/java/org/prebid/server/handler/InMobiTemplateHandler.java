package org.prebid.server.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.velocity.Template;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.context.Context;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.io.File;
import java.io.IOException;

public class InMobiTemplateHandler {

    private VelocityEngine velocityEngine;

    private static final String BANNER_TEMPLATE_JSON = "src/main/resources/inmobi-templates/inmobi_sdk_response.json";
    private static final String TEMPLATE_HTML_PATH = "src/main/resources/inmobi-templates/";
    private static final String BANNER_HTML = "inmobi_banner_default.html";
    private static final String VIDEO_HTML = "inmobi_video_default.html";
    private static final String RESPONSE_KEY_IN_HTML = "DSP_ORTB_RESPONSE";
    private static final String VAST_KEY_IN_HTML = "VAST_CREATIVE";


    public InMobiTemplateHandler() {
        // Initialize Velocity Engine
        Properties bannerProperties = new Properties();
        bannerProperties.setProperty("resource.loader", "file");
        bannerProperties.setProperty("file.resource.loader.path", TEMPLATE_HTML_PATH); // Update with your template path
        velocityEngine = new VelocityEngine(bannerProperties);
        velocityEngine.init();


    }

    private String generateHtml(String bidResponseHtml, CreativeTypes creativeType) throws Exception {
        // Load the template
        Template template;
        // Create a context and add data
        Context context = new org.apache.velocity.VelocityContext();
        if(creativeType == CreativeTypes.BANNER){
            template = velocityEngine.getTemplate(BANNER_HTML);
            context.put(RESPONSE_KEY_IN_HTML, bidResponseHtml);
        }else{
            template = velocityEngine.getTemplate(VIDEO_HTML);
            context.put(VAST_KEY_IN_HTML, bidResponseHtml);
        }

        // Merge the template with the context
        StringWriter writer = new StringWriter();
        template.merge(context, writer);

        // Return the generated HTML
        return writer.toString();
    }

    private String updateAdmValue(String jsonFilePath, String pubContent, String creativeType) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        try{
            JsonNode rootNode = objectMapper.readTree(new File(jsonFilePath));
            // Navigate to the pubContent key
            JsonNode adSetsNode = rootNode.path("adSets");
            if (adSetsNode.isArray() && adSetsNode.size() > 0) {
                JsonNode adsNode = adSetsNode.get(0).path("ads");
                if (adsNode.isArray() && adsNode.size() > 0) {
                    // Get the pubContent node
                    ObjectNode adNode = (ObjectNode) adsNode.get(0);
                    adNode.put("pubContent", pubContent); // Update pubContent
                    ObjectNode metaNode = (ObjectNode) adNode.get("metaInfo");
                    metaNode.put("creativeType", creativeType);
                    return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(rootNode);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    public CreativeTypes getCreativeType(String pubContent){
        if(!pubContent.toLowerCase().contains("<vast")){
            System.out.println("### CREATIVE_TYPE ### banner" );
            return CreativeTypes.BANNER;
        }
        else {
            System.out.println("### CREATIVE_TYPE ### video" );
            return CreativeTypes.VIDEO;
        }
    }

    public String generateTemplateResponse(String bidResponseHtml){
        try {
            CreativeTypes creativeType = getCreativeType(bidResponseHtml);
            String inMobiTemplateHtml = generateHtml(bidResponseHtml, creativeType);
            String updatedJson = updateAdmValue(BANNER_TEMPLATE_JSON, inMobiTemplateHtml, creativeType.value);
            System.out.println(updatedJson);
            return updatedJson;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public enum CreativeTypes {
        VIDEO("video"),
        BANNER("nonvideo");

        private final String value;

        // Constructor for the enum
        CreativeTypes(String value) {
            this.value = value;
        }

        // Getter for the value
        public String getValue() {
            return value;
        }
    }

}
