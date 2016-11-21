/*
    Copyright 2010, Strategic Gains, Inc.

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
 */
package org.restnext.core.http.url;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UrlPattern leverages Regex Pattern to represent a parameterized URL. Parameters within the URL are
 * denoted by curly braces '{}' with the parameter name contained within (e.g. '{userid}').
 * <p>
 * Parameter names must be formed of word characters (e.g. A-Z, a-z, 0-9, '_').
 * </p>
 * <p>An optional format parameter following a dot ('.') may be added to the end.  While it could be named any valid parameter name,
 * RestExpress offers special handling (e.g. within the Request, etc.) if it's named 'format'.
 * </p>
 * Note that the format specifier allows only word characters and percent-encoded characters.
 * <p>
 * URL Pattern examples:
 * </p>
 * <ul>
 * <li>/api/search.{format}</li>
 * <li>/api/search/users/{userid}.{format}</li>
 * <li>/api/{version}/search/users/{userid}</li>
 * </ul>
 * <p>
 * RestExpress parses URI paths which is described in the URI Generic Syntax IETF RFC 3986 specifcation,
 * section 3.3 (http://tools.ietf.org/html/rfc3986#section-3.3). RestExpress parses paths into segments
 * separated by slashes ("/"), the segments of which are composed of unreserved, percent encoded,
 * sub-delimiters, colon (":") or asperand ("@"), each of which are defined below (from the spec):
 * </p>
 * <p>pct-encoded   = "%" HEXDIG HEXDIG</p>
 * <p>unreserved    = ALPHA / DIGIT / "-" / "." / "_" / "~"</p>
 * <p>reserved      = gen-delims / sub-delims</p>
 * <p>gen-delims    = / ":" / "/" / "?" / "#" / "[" / "]" / "@"</p>
 * <p>sub-delims    = / "!" / "$" / "&amp;&amp;" / "'" / "(" / ")" / "*" / "+" / "," / ";" / "=" / "*"</p>
 *
 * In other words, RestExpress accepts path segments containing: [A-Z] [a-z] [0-9] % - . _ ~ ! $ &amp;&amp; ' ( ) * + , ; = : @
 *
 * RestExpress also accepts square brackets ('[' and ']'), but this is deprecated and not recommended.
 *
 * @author toddf
 * @see <a href="http://www.ietf.org/rfc/rfc3986.txt">rfc3986</a>
 * @since Apr 28, 2010
 */
public class UrlPattern implements UrlMatcher {

    // SECTION: CONSTANTS

    // Finds parameters in the URL pattern string.
    private static final String URL_PARAM_REGEX = "\\{(\\w*?)\\}";

    // Replaces parameter names in the URL pattern string to match parameters in URLs.
    private static final String URL_PARAM_MATCH_REGEX = "\\([%\\\\w-.\\\\~!\\$&'\\\\(\\\\)\\\\*\\\\+,;=:\\\\[\\\\]@]+?\\)";

    // Pattern to match URL pattern parameter names.
    private static final Pattern URL_PARAM_PATTERN = Pattern.compile(URL_PARAM_REGEX);

    // Finds the 'format' portion of the URL pattern string.
    private static final String URL_FORMAT_REGEX = "(?:\\.\\{format\\})$";

    // Replaces the format parameter name in the URL pattern string to match the format specifier in URLs. Appended to the end of the regex string
    // when a URL pattern contains a format parameter.
    private static final String URL_FORMAT_MATCH_REGEX = "(?:\\\\.\\([\\\\w%]+?\\))?";

    // Finds the query string portion within a URL. Appended to the end of the built-up regex string.
    private static final String URL_QUERY_STRING_REGEX = "(?:\\?.*?)?$";

    /**
     * The URL pattern describing the URL layout and any parameters.
     */
    private String urlPattern;

    /**
     * A compiled regex created from the urlPattern, above.
     */
    private Pattern compiledUrl;

    /**
     * An ordered list of parameter names found in the urlPattern, above.
     */
    private List<String> parameterNames = new ArrayList<>();


    // SECTION: CONSTRUCTOR

    /**
     * @param path path
     */
    public UrlPattern(String path) {
        super();
        setUrlPattern(toRegexPattern(path));
        compile();
    }

    // SECTION: ACCESSORS/MUTATORS - PRIVATE

    /**
     * @return the pattern
     */
    private String getUrlPattern() {
        return urlPattern;
    }

    public String getPattern() {
        return getUrlPattern().replaceFirst(URL_FORMAT_REGEX, "");
    }

    /**
     * @param pattern the pattern to set
     */
    private void setUrlPattern(String pattern) {
        this.urlPattern = pattern;
    }

    public List<String> getParameterNames() {
        return Collections.unmodifiableList(parameterNames);
    }

    // SECTION: URL MATCHING

    /**
     * Test the given URL against the underlying pattern to determine if it matches, returning the
     * results in a UrlMatch instance.  If the URL matches, parse any applicable parameters from it,
     * placing those also in the UrlMatch instance accessible by their parameter names.
     *
     * @param url an URL string with or without query string.
     * @return a UrlMatch instance reflecting the outcome of the comparison, if matched. Otherwise, null.
     */
    @Override
    public UrlMatch match(String url) {
        Matcher matcher = compiledUrl.matcher(url);

        if (matcher.matches()) {
            return new UrlMatch(extractParameters(matcher));
        }

        return null;
    }

    /**
     * Test the given URL against the underlying pattern to determine if it matches, returning a boolean
     * to reflect the outcome.
     *
     * @param url an URL string with or without query string.
     * @return true if the given URL matches the underlying pattern.  Otherwise false.
     */
    @Override
    public boolean matches(String url) {
        return (match(url) != null);
    }

    @Override
    public String toRegexPattern(String url) {
        String pattern = url;
        if (pattern != null && !pattern.startsWith("/")) {
            pattern = "/" + pattern;
        }
        return pattern;
    }

    // SECTION: UTILITY - PRIVATE

    /**
     * Processes the incoming URL pattern string to create a java.classpath.regex Pattern out of it and
     * parse out the parameter names, if applicable.
     */
    public void compile() {
        acquireParameterNames();
        String parsedPattern = getUrlPattern().replaceFirst(URL_FORMAT_REGEX, URL_FORMAT_MATCH_REGEX);
        parsedPattern = parsedPattern.replaceAll(URL_PARAM_REGEX, URL_PARAM_MATCH_REGEX);
        this.compiledUrl = Pattern.compile(parsedPattern + URL_QUERY_STRING_REGEX);
    }

    /**
     * Parses the parameter names from the URL pattern string provided to the constructor, building
     * the ordered list, parameterNames.
     */
    private void acquireParameterNames() {
        Matcher m = URL_PARAM_PATTERN.matcher(getUrlPattern());

        while (m.find()) {
            parameterNames.add(m.group(1));
        }
    }

    /**
     * Extracts parameter values from a Matcher instance using the regular expression groupings.
     *
     * @param matcher
     * @return a Map containing parameter values indexed by their corresponding parameter name.
     */
    private Map<String, String> extractParameters(Matcher matcher) {
        Map<String, String> values = new HashMap<>();

        for (int i = 0; i < matcher.groupCount(); i++) {
            String value = matcher.group(i + 1);

            if (value != null) {
                values.put(parameterNames.get(i), value);
            }
        }

        return values;
    }
}