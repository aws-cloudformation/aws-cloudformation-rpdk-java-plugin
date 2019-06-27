// This is a generated file. Modifications will be overwritten.
package {{ package_name }};

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.json.JSONObject;


@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class {{ pojo_name|uppercase_first_letter }} {
    {% if pojo_name == "ResourceModel" %}

    public static final String TYPE_NAME = "{{ type_name }}";

    {% for identifier in primaryIdentifier %}
        {% set components = identifier.split("/") %}
    public static final String IDENTIFIER_KEY_{{ components[2:]|join('_')|upper }} = "{{ identifier }}";
    {% endfor -%}

    {% for identifiers in additionalIdentifiers %}
    {% for identifier in identifiers %}
    {% set components = identifier.split("/") %}
    public static final String IDENTIFIER_KEY_{{ components[2:]|join('_')|upper }} = "{{ identifier }}";
    {% endfor %}
    {% endfor %}
    {% endif %}

    {% for name, type in properties.items() %}
    @JsonProperty("{{ name }}")
    private {{ type }} {{ name|lowercase_first_letter }};

    {% endfor %}
    {% if pojo_name == "ResourceModel" %}
    public JSONObject getPrimaryIdentifier() {
        final JSONObject identifier = new JSONObject();
        {% for identifier in primaryIdentifier %}
        {% set components = identifier.split("/") %}
        if (this.get{{components[2]}}() != null
            {%- for i in range(4, components|length + 1) -%}
                {#- #} && this
                {%- for component in components[2:i] -%} .get{{component|uppercase_first_letter}}() {%- endfor -%}
                {#- #} != null
            {%- endfor -%}
        ) {
            identifier.append(IDENTIFIER_KEY_{{ components[2:]|join('_')|upper }}, this{% for component in components[2:] %}.get{{component|uppercase_first_letter}}(){% endfor %});
        }

        {% endfor %}
        // only return the identifier if it can be used, i.e. if all components are present
        return identifier.length() == {{ primaryIdentifier|length }} ? identifier : null;
    }

    public List<JSONObject> getAdditionalIdentifiers() {
        final List<JSONObject> identifiers = new ArrayList<JSONObject>();
        {% for identifiers in additionalIdentifiers %}
        if (getIdentifier {%- for identifier in identifiers -%} _{{identifier.split("/")[-1]|uppercase_first_letter}} {%- endfor -%} () != null) {
            identifiers.add(getIdentifier{% for identifier in identifiers %}_{{identifier.split("/")[-1]|uppercase_first_letter}}{% endfor %}());
        }
        {% endfor %}
        // only return the identifiers if any can be used
        return identifiers.isEmpty() ? null : identifiers;
    }
    {% for identifiers in additionalIdentifiers %}

    public JSONObject getIdentifier {%- for identifier in identifiers -%} _{{identifier.split("/")[-1]|uppercase_first_letter}} {%- endfor -%} () {
        final JSONObject identifier = new JSONObject();
        {% for identifier in identifiers %}
        {% set components = identifier.split("/") %}
        if (this.get{{components[2]}}() != null
            {%- for i in range(4, components|length + 1) -%}
                {#- #} && this
                {%- for component in components[2:i] -%} .get{{component|uppercase_first_letter}}() {%- endfor -%}
                {#- #} != null
            {%- endfor -%}
        ) {
            identifier.append(IDENTIFIER_KEY_{{ components[2:]|join('_')|upper }}, this{% for component in components[2:] %}.get{{component|uppercase_first_letter}}(){% endfor %});
        }

        {% endfor %}
        // only return the identifier if it can be used, i.e. if all components are present
        return identifier.length() == {{ identifiers|length }} ? identifier : null;
    }
    {% endfor %}
    {% endif %}
}
