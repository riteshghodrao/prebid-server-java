package org.prebid.server.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.fge.jsonpatch.JsonPatchException;
import org.apache.commons.lang3.ObjectUtils;
import org.prebid.server.exception.InvalidRequestException;
import org.prebid.server.json.merge.JsonMergePatch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class JsonMerger {

    private final JacksonMapper mapper;

    public JsonMerger(JacksonMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    /**
     * Merges passed object with json retrieved from stored data map by id
     * and cast it to appropriate class. In case of any exception during merging, throws {@link InvalidRequestException}
     * with reason message.
     */
    public <T> T merge(T originalObject, String storedData, String id, Class<T> classToCast) {
        final JsonNode originJsonNode = mapper.mapper().valueToTree(originalObject);
        stripNulls(originJsonNode);
        final JsonNode storedRequestJsonNode;
        try {
            storedRequestJsonNode = mapper.mapper().readTree(storedData);
        } catch (IOException e) {
            throw new InvalidRequestException("Can't parse Json for stored request with id " + id);
        }
        try {
            return mapper.mapper().treeToValue(
                    JsonMergePatch.fromJson(originJsonNode).apply(storedRequestJsonNode),
                    classToCast);
        } catch (JsonPatchException e) {
            throw new InvalidRequestException(
                    "Couldn't create merge patch from origin object node for id %s: %s".formatted(id, e.getMessage()));
        } catch (JsonProcessingException e) {
            throw new InvalidRequestException(
                    "Can't convert merging result for id %s: %s".formatted(id, e.getMessage()));
        }
    }

    public <T> T merge(T originalObject, T mergingObject, Class<T> classToCast) {
        if (!ObjectUtils.allNotNull(originalObject, mergingObject)) {
            return ObjectUtils.defaultIfNull(originalObject, mergingObject);
        }

        final JsonNode originJsonNode = mapper.mapper().valueToTree(originalObject);
        final JsonNode mergingObjectJsonNode = mapper.mapper().valueToTree(mergingObject);
        try {
            final JsonNode mergedNode = JsonMergePatch.fromJson(originJsonNode).apply(mergingObjectJsonNode);
            return mapper.mapper().treeToValue(mergedNode, classToCast);
        } catch (JsonPatchException e) {
            throw new InvalidRequestException(
                    "Couldn't create merge patch for objects with class " + classToCast.getName());
        } catch (JsonProcessingException e) {
            throw new InvalidRequestException("Can't convert merging result class " + classToCast.getName());
        }
    }

    /**
     * Returns 'toNode' with merged properties from 'fromNode'
     * <p>
     * fromNode object fields has priority over the toNode
     */
    public JsonNode merge(JsonNode fromNode, JsonNode toNode) {
        try {
            return JsonMergePatch.fromJson(fromNode).apply(toNode);
        } catch (JsonPatchException e) {
            throw new InvalidRequestException("Couldn't create merge patch for json nodes");
        }
    }

    private static void stripNulls(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            final List<String> nullFields = new ArrayList<>();
            final Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                final Map.Entry<String, JsonNode> field = fields.next();
                if (field.getValue().isNull()) {
                    nullFields.add(field.getKey());
                } else {
                    stripNulls(field.getValue());
                }
            }
            objectNode.remove(nullFields);
        }
    }
}
