/*   **********************************************************************  **
 **   Copyright notice                                                       **
 **                                                                          **
 **   (c) 2005-2009 RSSOwl Development Team                                  **
 **   http://www.rssowl.org/                                                 **
 **                                                                          **
 **   All rights reserved                                                    **
 **                                                                          **
 **   This program and the accompanying materials are made available under   **
 **   the terms of the Eclipse Public License v1.0 which accompanies this    **
 **   distribution, and is available at:                                     **
 **   http://www.rssowl.org/legal/epl-v10.html                               **
 **                                                                          **
 **   A copy is found in the file epl-v10.html and important notices to the  **
 **   license from the team is found in the textfile LICENSE.txt distributed **
 **   in this package.                                                       **
 **                                                                          **
 **   This copyright notice MUST APPEAR in all copies of the file!           **
 **                                                                          **
 **   Contributors:                                                          **
 **     RSSOwl Development Team - initial API and implementation             **
 **                                                                          **
 **  **********************************************************************  */

package org.rssowl.ui.internal.util;

import org.eclipse.core.runtime.IProgressMonitor;
import org.rssowl.core.internal.interpreter.json.JSONArray;
import org.rssowl.core.internal.interpreter.json.JSONException;
import org.rssowl.core.internal.interpreter.json.JSONObject;
import org.rssowl.core.util.StringUtils;
import org.rssowl.ui.internal.Activator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight in-memory translation overlay for the current RSSOwl session.
 */
public class FeedTranslationManager {
  private static final FeedTranslationManager INSTANCE = new FeedTranslationManager();

  private static final String GOOGLE_TRANSLATE_HOST = "https://translate.google.com"; //$NON-NLS-1$
  private static final String MYMEMORY_TRANSLATE_HOST = "https://api.mymemory.translated.net"; //$NON-NLS-1$
  private static final String USER_AGENT = "Mozilla/5.0 RSSOwl Translate"; //$NON-NLS-1$
  private static final int CONNECT_TIMEOUT = 12000;
  private static final int READ_TIMEOUT = 25000;
  private static final int MAX_CHUNK_LENGTH = 4500;
  private static final int MAX_REQUEST_RETRIES = 2;
  private static final long REQUEST_DELAY = 500L;
  private static final long TOO_MANY_REQUESTS_COOLDOWN = 60000L;

  private final Map<String, String> fTranslations = Collections.synchronizedMap(new HashMap<String, String>());
  private volatile long fBlockedUntil;

  public static FeedTranslationManager getDefault() {
    return INSTANCE;
  }

  public String getTranslatedText(String text) {
    String translated = findTranslatedText(text);
    return translated != null ? translated : text;
  }

  public String findTranslatedText(String text) {
    String key = normalizeKey(text);
    if (key == null)
      return null;

    return fTranslations.get(key);
  }

  public String getTranslatedBrowserContent(String htmlContent) {
    String plainText = toPlainText(htmlContent);
    if (!StringUtils.isSet(plainText))
      return null;

    String translated = findTranslatedText(plainText);
    if (!StringUtils.isSet(translated))
      return null;

    return formatPlainTextForHtml(translated);
  }

  public void translateMissingTexts(Collection<String> texts, IProgressMonitor monitor) {
    translateMissingTexts(texts, null, monitor);
  }

  public void translateMissingTexts(Collection<String> texts, String sourceLanguage, IProgressMonitor monitor) {
    if (texts == null || texts.isEmpty())
      return;

    String targetLanguage = getTargetLanguage();
    LinkedHashSet<String> uniqueTexts = new LinkedHashSet<String>();
    for (String text : texts) {
      String key = normalizeKey(text);
      if (key == null || fTranslations.containsKey(key))
        continue;

      uniqueTexts.add(key);
    }

    if (uniqueTexts.isEmpty())
      return;

    if (monitor != null)
      monitor.beginTask("translate", uniqueTexts.size()); //$NON-NLS-1$

    for (String text : uniqueTexts) {
      if (monitor != null && monitor.isCanceled())
        break;

      try {
        String translated = translateText(text, sourceLanguage, targetLanguage, monitor);
        if (!StringUtils.isSet(translated))
          continue;

        fTranslations.put(text, translated);
      } catch (IOException e) {
        Activator.getDefault().logError(e.getMessage(), e);
        if (isTooManyRequests(e))
          break;
      } finally {
        if (monitor != null)
          monitor.worked(1);
      }
    }

    if (monitor != null)
      monitor.done();
  }

  public String toPlainText(String htmlContent) {
    if (!StringUtils.isSet(htmlContent))
      return null;

    String plainText = StringUtils.stripTags(htmlContent, true);
    return normalizeKey(plainText);
  }

  private String translateText(String text, String sourceLanguage, String targetLanguage, IProgressMonitor monitor) throws IOException {
    String normalized = normalizeKey(text);
    if (!StringUtils.isSet(normalized))
      return text;

    List<String> chunks = chunkText(normalized);
    StringBuilder translated = new StringBuilder(normalized.length());
    for (String chunk : chunks) {
      if (monitor != null && monitor.isCanceled())
        break;

      String translatedChunk = requestTranslationWithRetry(chunk, sourceLanguage, targetLanguage);
      appendChunk(translated, translatedChunk);
    }

    String result = normalizeKey(translated.toString());
    return StringUtils.isSet(result) ? result : normalized;
  }

  private String requestTranslationWithRetry(String chunk, String sourceLanguage, String targetLanguage) throws IOException {
    if (!isGoogleTemporarilyBlocked()) {
      try {
        return requestGoogleTranslationWithRetry(chunk, targetLanguage);
      } catch (IOException e) {
        // Google is best-effort only. If it fails, continue with the fallback provider.
      }
    }

    try {
      return requestMyMemoryTranslationWithFallbackSources(chunk, sourceLanguage, targetLanguage);
    } catch (IOException fallbackException) {
      throw fallbackException;
    }
  }

  private String requestGoogleTranslationWithRetry(String chunk, String targetLanguage) throws IOException {
    IOException lastException = null;
    for (int attempt = 0; attempt < MAX_REQUEST_RETRIES; attempt++) {
      try {
        waitBeforeGoogleRequest();
        return requestGoogleTranslation(chunk, targetLanguage);
      } catch (IOException e) {
        lastException = e;
        if (isTooManyRequests(e)) {
          fBlockedUntil = System.currentTimeMillis() + TOO_MANY_REQUESTS_COOLDOWN;
          break;
        }

        if (attempt + 1 >= MAX_REQUEST_RETRIES)
          break;

        try {
          Thread.sleep(250L * (attempt + 1));
        } catch (InterruptedException interruptedException) {
          Thread.currentThread().interrupt();

          IOException ioException = new IOException(interruptedException.getMessage());
          ioException.initCause(interruptedException);
          throw ioException;
        }
      }
    }

    throw lastException;
  }

  private boolean isGoogleTemporarilyBlocked() {
    return System.currentTimeMillis() < fBlockedUntil;
  }

  private boolean isTooManyRequests(IOException e) {
    String message = e.getMessage();
    return message != null && message.indexOf("HTTP 429") >= 0; //$NON-NLS-1$
  }

  private void waitBeforeGoogleRequest() throws IOException {
    long blockedUntil = fBlockedUntil;
    long now = System.currentTimeMillis();
    if (now < blockedUntil)
      throw new IOException("Google Translate is temporarily blocked after HTTP 429."); //$NON-NLS-1$

    try {
      Thread.sleep(REQUEST_DELAY);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();

      IOException ioException = new IOException(e.getMessage());
      ioException.initCause(e);
      throw ioException;
    }
  }

  private String requestGoogleTranslation(String chunk, String targetLanguage) throws IOException {
    String token = buildToken(chunk);
    String googleTargetLanguage = toGoogleLanguageCode(targetLanguage);
    StringBuilder urlBuilder = new StringBuilder(GOOGLE_TRANSLATE_HOST);
    urlBuilder.append("/translate_a/single?client=gtx&sl=auto"); //$NON-NLS-1$
    urlBuilder.append("&tl=").append(URLEncoder.encode(googleTargetLanguage, "UTF-8")); //$NON-NLS-1$ //$NON-NLS-2$
    urlBuilder.append("&hl=").append(URLEncoder.encode(googleTargetLanguage, "UTF-8")); //$NON-NLS-1$ //$NON-NLS-2$
    urlBuilder.append("&dt=bd&dt=t&dt=ld&dt=rm&ie=UTF-8&oe=UTF-8&tk=").append(token); //$NON-NLS-1$

    HttpURLConnection connection = null;
    OutputStream out = null;
    InputStream in = null;
    try {
      connection = (HttpURLConnection) new URI(urlBuilder.toString()).toURL().openConnection();
      connection.setConnectTimeout(CONNECT_TIMEOUT);
      connection.setReadTimeout(READ_TIMEOUT);
      connection.setRequestMethod("POST"); //$NON-NLS-1$
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8"); //$NON-NLS-1$ //$NON-NLS-2$
      connection.setRequestProperty("User-Agent", USER_AGENT); //$NON-NLS-1$
      connection.setRequestProperty("Accept-Language", googleTargetLanguage); //$NON-NLS-1$

      byte[] body = ("q=" + URLEncoder.encode(chunk, "UTF-8")).getBytes("UTF-8"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
      out = connection.getOutputStream();
      out.write(body);
      out.flush();

      int responseCode = connection.getResponseCode();
      in = responseCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
      String response = readFully(in);
      if (responseCode >= 400)
        throw new IOException("Google Translate request failed: HTTP " + responseCode + " - " + abbreviate(response)); //$NON-NLS-1$ //$NON-NLS-2$

      String translated = parseTranslation(response);
      return StringUtils.isSet(translated) ? translated : chunk;
    } catch (URISyntaxException e) {
      IOException ioException = new IOException(e.getMessage());
      ioException.initCause(e);
      throw ioException;
    } finally {
      if (out != null) {
        try {
          out.close();
        } catch (IOException e) {
          Activator.getDefault().logError(e.getMessage(), e);
        }
      }

      if (in != null) {
        try {
          in.close();
        } catch (IOException e) {
          Activator.getDefault().logError(e.getMessage(), e);
        }
      }

      if (connection != null)
        connection.disconnect();
    }
  }

  private String requestMyMemoryTranslationWithFallbackSources(String chunk, String sourceLanguage, String targetLanguage) throws IOException {
    List<String> sourceLanguages = resolveMyMemorySourceLanguages(sourceLanguage, chunk, targetLanguage);
    if (sourceLanguages.isEmpty())
      throw new IOException("No suitable source language available for MyMemory."); //$NON-NLS-1$

    IOException lastException = null;
    for (String candidateSourceLanguage : sourceLanguages) {
      try {
        String translated = requestMyMemoryTranslation(chunk, candidateSourceLanguage, targetLanguage);
        if (StringUtils.isSet(translated))
          return translated;
      } catch (IOException e) {
        lastException = e;
      }
    }

    throw lastException != null ? lastException : new IOException("MyMemory translation did not return a usable translation."); //$NON-NLS-1$
  }

  private String requestMyMemoryTranslation(String chunk, String sourceLanguage, String targetLanguage) throws IOException {
    HttpURLConnection connection = null;
    InputStream in = null;
    try {
      StringBuilder urlBuilder = new StringBuilder(MYMEMORY_TRANSLATE_HOST);
      urlBuilder.append("/get?q=").append(URLEncoder.encode(chunk, "UTF-8")); //$NON-NLS-1$ //$NON-NLS-2$
      urlBuilder.append("&langpair=").append(URLEncoder.encode(sourceLanguage, "UTF-8")); //$NON-NLS-1$ //$NON-NLS-2$
      urlBuilder.append("%7C").append(URLEncoder.encode(targetLanguage, "UTF-8")); //$NON-NLS-1$

      connection = (HttpURLConnection) new URI(urlBuilder.toString()).toURL().openConnection();
      connection.setConnectTimeout(CONNECT_TIMEOUT);
      connection.setReadTimeout(READ_TIMEOUT);
      connection.setRequestMethod("GET"); //$NON-NLS-1$
      connection.setRequestProperty("User-Agent", USER_AGENT); //$NON-NLS-1$
      connection.setRequestProperty("Accept-Language", targetLanguage); //$NON-NLS-1$

      int responseCode = connection.getResponseCode();
      in = responseCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
      String response = readFully(in);
      if (responseCode >= 400)
        throw new IOException("MyMemory request failed: HTTP " + responseCode + " - " + abbreviate(response)); //$NON-NLS-1$ //$NON-NLS-2$

      String translated = parseMyMemoryTranslation(response, chunk, sourceLanguage, targetLanguage);
      if (StringUtils.isSet(translated))
        return translated;

      throw new IOException("MyMemory translation was empty for source language " + sourceLanguage + "."); //$NON-NLS-1$ //$NON-NLS-2$
    } catch (URISyntaxException e) {
      IOException ioException = new IOException(e.getMessage());
      ioException.initCause(e);
      throw ioException;
    } finally {
      if (in != null) {
        try {
          in.close();
        } catch (IOException e) {
          Activator.getDefault().logError(e.getMessage(), e);
        }
      }

      if (connection != null)
        connection.disconnect();
    }
  }

  private String parseTranslation(String response) throws IOException {
    try {
      JSONArray root = new JSONArray(response);
      JSONArray sentences = root.optJSONArray(0);
      if (sentences == null)
        return null;

      StringBuilder translated = new StringBuilder();
      for (int i = 0; i < sentences.length(); i++) {
        JSONArray sentence = sentences.optJSONArray(i);
        if (sentence != null)
          translated.append(sentence.optString(0));
      }

      return translated.toString();
    } catch (JSONException e) {
      throw new IOException("Unable to parse Google Translate response.", e); //$NON-NLS-1$
    }
  }

  private String parseMyMemoryTranslation(String response, String originalText, String sourceLanguage, String targetLanguage) throws IOException {
    try {
      JSONObject root = new JSONObject(response);
      Object statusObject = root.opt("responseStatus"); //$NON-NLS-1$
      int responseStatus = statusObject instanceof Number ? ((Number) statusObject).intValue() : Integer.parseInt(String.valueOf(statusObject));
      if (responseStatus != 200) {
        String details = root.optString("responseDetails"); //$NON-NLS-1$
        throw new IOException("MyMemory request failed: HTTP " + responseStatus + " - " + abbreviate(details)); //$NON-NLS-1$ //$NON-NLS-2$
      }

      JSONObject responseData = root.optJSONObject("responseData"); //$NON-NLS-1$
      if (responseData == null)
        return null;

      String translated = normalizeKey(responseData.optString("translatedText")); //$NON-NLS-1$
      if (!StringUtils.isSet(translated))
        return null;

      if (!isUsefulTranslation(originalText, translated, sourceLanguage, targetLanguage))
        return null;

      return translated;
    } catch (JSONException e) {
      throw new IOException("Unable to parse MyMemory response.", e); //$NON-NLS-1$
    } catch (NumberFormatException e) {
      throw new IOException("Unable to parse MyMemory response status.", e); //$NON-NLS-1$
    }
  }

  private String readFully(InputStream in) throws IOException {
    if (in == null)
      return ""; //$NON-NLS-1$

    BufferedReader reader = null;
    try {
      reader = new BufferedReader(new InputStreamReader(in, "UTF-8")); //$NON-NLS-1$
      return StringUtils.readString(reader);
    } finally {
      if (reader != null)
        reader.close();
    }
  }

  private String abbreviate(String text) {
    if (text == null)
      return ""; //$NON-NLS-1$

    String normalized = normalizeKey(text);
    if (normalized == null)
      return ""; //$NON-NLS-1$

    int maxLength = 400;
    if (normalized.length() <= maxLength)
      return normalized;

    return normalized.substring(0, maxLength) + "..."; //$NON-NLS-1$
  }

  private List<String> chunkText(String text) {
    List<String> chunks = new ArrayList<String>();
    String remainder = text;
    while (StringUtils.isSet(remainder)) {
      if (remainder.length() <= MAX_CHUNK_LENGTH) {
        chunks.add(remainder);
        break;
      }

      int splitIndex = findSplitIndex(remainder);
      if (splitIndex <= 0)
        splitIndex = MAX_CHUNK_LENGTH;

      String chunk = normalizeKey(remainder.substring(0, splitIndex));
      if (!StringUtils.isSet(chunk))
        chunk = remainder.substring(0, MAX_CHUNK_LENGTH);

      chunks.add(chunk);
      remainder = normalizeKey(remainder.substring(splitIndex));
    }

    return chunks;
  }

  private int findSplitIndex(String text) {
    int maxIndex = Math.min(text.length(), MAX_CHUNK_LENGTH);
    String candidate = text.substring(0, maxIndex);

    int splitIndex = candidate.lastIndexOf('\n');
    if (splitIndex > 0)
      return splitIndex + 1;

    for (int i = candidate.length() - 1; i >= 0; i--) {
      char ch = candidate.charAt(i);
      if (ch == '.' || ch == '!' || ch == '?' || ch == ';' || ch == ':' || ch == ',' || Character.isWhitespace(ch))
        return i + 1;
    }

    return maxIndex;
  }

  private void appendChunk(StringBuilder builder, String chunk) {
    if (!StringUtils.isSet(chunk))
      return;

    if (builder.length() == 0) {
      builder.append(chunk);
      return;
    }

    char previous = builder.charAt(builder.length() - 1);
    char next = chunk.charAt(0);
    if (!Character.isWhitespace(previous) && !Character.isWhitespace(next) && !isPunctuation(previous) && !isPunctuation(next))
      builder.append(' ');

    builder.append(chunk);
  }

  private boolean isPunctuation(char ch) {
    return ch == '.' || ch == ',' || ch == '!' || ch == '?' || ch == ';' || ch == ':' || ch == ')' || ch == '(' || ch == '"' || ch == '\'';
  }

  private String formatPlainTextForHtml(String text) {
    String escaped = StringUtils.htmlEscape(text);
    escaped = StringUtils.replaceAll(escaped, "\r\n", "\n"); //$NON-NLS-1$ //$NON-NLS-2$
    escaped = StringUtils.replaceAll(escaped, "\r", "\n"); //$NON-NLS-1$ //$NON-NLS-2$
    return StringUtils.replaceAll(escaped, "\n", "<br/>"); //$NON-NLS-1$ //$NON-NLS-2$
  }

  private String normalizeKey(String text) {
    if (!StringUtils.isSet(text))
      return null;

    return StringUtils.normalizeString(text).trim();
  }

  private boolean isUsefulTranslation(String originalText, String translatedText, String sourceLanguage, String targetLanguage) {
    String normalizedOriginal = normalizeKey(originalText);
    String normalizedTranslated = normalizeKey(translatedText);
    if (!StringUtils.isSet(normalizedOriginal) || !StringUtils.isSet(normalizedTranslated))
      return false;

    if (!normalizedOriginal.equals(normalizedTranslated))
      return true;

    String normalizedSourceLanguage = normalizeLanguageCode(sourceLanguage);
    String normalizedTargetLanguage = normalizeLanguageCode(targetLanguage);
    return StringUtils.isSet(normalizedSourceLanguage) && normalizedSourceLanguage.equals(normalizedTargetLanguage);
  }

  private String getTargetLanguage() {
    Locale locale = Locale.getDefault();
    String language = locale.getLanguage();
    if (!StringUtils.isSet(language))
      return "en"; //$NON-NLS-1$

    if ("zh".equals(language)) { //$NON-NLS-1$
      String country = locale.getCountry();
      if ("TW".equalsIgnoreCase(country) || "HK".equalsIgnoreCase(country)) //$NON-NLS-1$ //$NON-NLS-2$
        return "zh-TW"; //$NON-NLS-1$

      return "zh-CN"; //$NON-NLS-1$
    }

    return language;
  }

  private String toGoogleLanguageCode(String language) {
    String normalizedLanguage = normalizeLanguageCode(language);
    if (!StringUtils.isSet(normalizedLanguage))
      return "en"; //$NON-NLS-1$

    if ("he".equals(normalizedLanguage)) //$NON-NLS-1$
      return "iw"; //$NON-NLS-1$

    return normalizedLanguage;
  }

  private List<String> resolveMyMemorySourceLanguages(String sourceLanguage, String text, String targetLanguage) {
    LinkedHashSet<String> languages = new LinkedHashSet<String>();
    String detectedLanguage = detectLanguageFromText(text);
    String normalizedSourceLanguage = normalizeLanguageCode(sourceLanguage);

    addLanguageCandidate(languages, detectedLanguage, targetLanguage);

    if (shouldUseSourceLanguageHint(normalizedSourceLanguage, detectedLanguage, text))
      addLanguageCandidate(languages, normalizedSourceLanguage, targetLanguage);

    addScriptFallbackLanguages(languages, text, detectedLanguage, targetLanguage);

    return new ArrayList<String>(languages);
  }

  private boolean shouldUseSourceLanguageHint(String sourceLanguage, String detectedLanguage, String text) {
    if (!StringUtils.isSet(sourceLanguage))
      return false;

    if (!StringUtils.isSet(detectedLanguage))
      return true;

    if (sourceLanguage.equals(detectedLanguage))
      return true;

    if (containsCyrillic(text))
      return "ru".equals(sourceLanguage) || "uk".equals(sourceLanguage); //$NON-NLS-1$ //$NON-NLS-2$
    if (containsArabic(text))
      return "ar".equals(sourceLanguage); //$NON-NLS-1$
    if (containsGreek(text))
      return "el".equals(sourceLanguage); //$NON-NLS-1$
    if (containsHebrew(text))
      return "he".equals(sourceLanguage); //$NON-NLS-1$
    if (containsCjk(text))
      return sourceLanguage.startsWith("zh") || "ja".equals(sourceLanguage) || "ko".equals(sourceLanguage); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    return containsLatinLetters(text);
  }

  private void addScriptFallbackLanguages(Collection<String> languages, String text, String detectedLanguage, String targetLanguage) {
    if (containsCyrillic(text)) {
      addLanguageCandidate(languages, "ru", targetLanguage); //$NON-NLS-1$
      addLanguageCandidate(languages, "uk", targetLanguage); //$NON-NLS-1$
      addLanguageCandidate(languages, "bg", targetLanguage); //$NON-NLS-1$
      return;
    }

    if (containsArabic(text)) {
      addLanguageCandidate(languages, "ar", targetLanguage); //$NON-NLS-1$
      return;
    }

    if (containsGreek(text)) {
      addLanguageCandidate(languages, "el", targetLanguage); //$NON-NLS-1$
      return;
    }

    if (containsHebrew(text)) {
      addLanguageCandidate(languages, "he", targetLanguage); //$NON-NLS-1$
      return;
    }

    if (containsCjk(text)) {
      addLanguageCandidate(languages, detectedLanguage, targetLanguage);
      addLanguageCandidate(languages, "zh-CN", targetLanguage); //$NON-NLS-1$
      addLanguageCandidate(languages, "ja", targetLanguage); //$NON-NLS-1$
      addLanguageCandidate(languages, "ko", targetLanguage); //$NON-NLS-1$
      return;
    }

    if (containsLatinLetters(text)) {
      addLanguageCandidate(languages, "en", targetLanguage); //$NON-NLS-1$
      addLanguageCandidate(languages, "fr", targetLanguage); //$NON-NLS-1$
      addLanguageCandidate(languages, "es", targetLanguage); //$NON-NLS-1$
      addLanguageCandidate(languages, "it", targetLanguage); //$NON-NLS-1$
      addLanguageCandidate(languages, "pt", targetLanguage); //$NON-NLS-1$
      addLanguageCandidate(languages, "nl", targetLanguage); //$NON-NLS-1$
      addLanguageCandidate(languages, "pl", targetLanguage); //$NON-NLS-1$
      addLanguageCandidate(languages, "tr", targetLanguage); //$NON-NLS-1$
    }
  }

  private void addLanguageCandidate(Collection<String> languages, String language, String targetLanguage) {
    String normalizedLanguage = normalizeLanguageCode(language);
    String normalizedTargetLanguage = normalizeLanguageCode(targetLanguage);
    if (!StringUtils.isSet(normalizedLanguage))
      return;

    if (StringUtils.isSet(normalizedTargetLanguage) && normalizedTargetLanguage.equals(normalizedLanguage))
      return;

    languages.add(normalizedLanguage);
  }

  private String normalizeLanguageCode(String language) {
    if (!StringUtils.isSet(language))
      return null;

    String normalized = language.trim();
    if (normalized.length() == 0)
      return null;

    normalized = normalized.replace('_', '-');
    String lowerCase = normalized.toLowerCase(Locale.US);

    if ("iw".equals(lowerCase)) //$NON-NLS-1$
      return "he"; //$NON-NLS-1$

    if ("english".equals(lowerCase)) //$NON-NLS-1$
      return "en"; //$NON-NLS-1$
    if ("german".equals(lowerCase) || "deutsch".equals(lowerCase)) //$NON-NLS-1$ //$NON-NLS-2$
      return "de"; //$NON-NLS-1$
    if ("russian".equals(lowerCase) || "russisch".equals(lowerCase)) //$NON-NLS-1$ //$NON-NLS-2$
      return "ru"; //$NON-NLS-1$
    if ("ukrainian".equals(lowerCase) || "ukrainisch".equals(lowerCase)) //$NON-NLS-1$ //$NON-NLS-2$
      return "uk"; //$NON-NLS-1$
    if ("french".equals(lowerCase) || "francais".equals(lowerCase) || "franzosisch".equals(lowerCase)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
      return "fr"; //$NON-NLS-1$

    if (lowerCase.startsWith("zh")) { //$NON-NLS-1$
      if (lowerCase.indexOf("tw") >= 0 || lowerCase.indexOf("hk") >= 0) //$NON-NLS-1$ //$NON-NLS-2$
        return "zh-TW"; //$NON-NLS-1$

      return "zh-CN"; //$NON-NLS-1$
    }

    int separatorIndex = lowerCase.indexOf('-');
    if (separatorIndex > 0) {
      String languagePart = lowerCase.substring(0, separatorIndex);
      String countryPart = lowerCase.substring(separatorIndex + 1).toUpperCase(Locale.US);
      if (languagePart.length() == 2 && countryPart.length() == 2)
        return languagePart + "-" + countryPart; //$NON-NLS-1$
    }

    if (lowerCase.length() >= 2 && Character.isLetter(lowerCase.charAt(0)) && Character.isLetter(lowerCase.charAt(1)))
      return lowerCase.substring(0, 2);

    return null;
  }

  private String detectLanguageFromText(String text) {
    if (!StringUtils.isSet(text))
      return null;

    boolean hasLatin = false;
    boolean hasCyrillic = false;
    boolean hasUkrainianMarker = false;
    boolean hasArabic = false;
    boolean hasGreek = false;
    boolean hasHebrew = false;
    boolean hasCjk = false;
    boolean hasJapanese = false;
    boolean hasKorean = false;

    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
      if (block == Character.UnicodeBlock.CYRILLIC || block == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY || block == Character.UnicodeBlock.CYRILLIC_EXTENDED_A || block == Character.UnicodeBlock.CYRILLIC_EXTENDED_B) {
        hasCyrillic = true;
        if (ch == '\u0454' || ch == '\u0404' || ch == '\u0456' || ch == '\u0406' || ch == '\u0457' || ch == '\u0407' || ch == '\u0491' || ch == '\u0490')
          hasUkrainianMarker = true;
      } else if (block == Character.UnicodeBlock.ARABIC) {
        hasArabic = true;
      } else if (block == Character.UnicodeBlock.GREEK || block == Character.UnicodeBlock.GREEK_EXTENDED) {
        hasGreek = true;
      } else if (block == Character.UnicodeBlock.HEBREW) {
        hasHebrew = true;
      } else if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) {
        hasCjk = true;
      } else if (block == Character.UnicodeBlock.HIRAGANA || block == Character.UnicodeBlock.KATAKANA) {
        hasJapanese = true;
      } else if (block == Character.UnicodeBlock.HANGUL_SYLLABLES || block == Character.UnicodeBlock.HANGUL_JAMO || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO) {
        hasKorean = true;
      } else if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (block == Character.UnicodeBlock.LATIN_1_SUPPLEMENT || block == Character.UnicodeBlock.LATIN_EXTENDED_A || block == Character.UnicodeBlock.LATIN_EXTENDED_B)) {
        hasLatin = true;
      }
    }

    if (hasJapanese)
      return "ja"; //$NON-NLS-1$
    if (hasKorean)
      return "ko"; //$NON-NLS-1$
    if (hasCjk)
      return "zh-CN"; //$NON-NLS-1$
    if (hasArabic)
      return "ar"; //$NON-NLS-1$
    if (hasGreek)
      return "el"; //$NON-NLS-1$
    if (hasHebrew)
      return "he"; //$NON-NLS-1$
    if (hasCyrillic)
      return hasUkrainianMarker ? "uk" : "ru"; //$NON-NLS-1$ //$NON-NLS-2$
    if (hasLatin)
      return "en"; //$NON-NLS-1$

    return null;
  }

  private boolean containsCyrillic(String text) {
    return containsUnicodeBlock(text, Character.UnicodeBlock.CYRILLIC)
        || containsUnicodeBlock(text, Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY)
        || containsUnicodeBlock(text, Character.UnicodeBlock.CYRILLIC_EXTENDED_A)
        || containsUnicodeBlock(text, Character.UnicodeBlock.CYRILLIC_EXTENDED_B);
  }

  private boolean containsArabic(String text) {
    return containsUnicodeBlock(text, Character.UnicodeBlock.ARABIC);
  }

  private boolean containsGreek(String text) {
    return containsUnicodeBlock(text, Character.UnicodeBlock.GREEK)
        || containsUnicodeBlock(text, Character.UnicodeBlock.GREEK_EXTENDED);
  }

  private boolean containsHebrew(String text) {
    return containsUnicodeBlock(text, Character.UnicodeBlock.HEBREW);
  }

  private boolean containsCjk(String text) {
    return containsUnicodeBlock(text, Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS)
        || containsUnicodeBlock(text, Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A)
        || containsUnicodeBlock(text, Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS)
        || containsUnicodeBlock(text, Character.UnicodeBlock.HIRAGANA)
        || containsUnicodeBlock(text, Character.UnicodeBlock.KATAKANA)
        || containsUnicodeBlock(text, Character.UnicodeBlock.HANGUL_SYLLABLES)
        || containsUnicodeBlock(text, Character.UnicodeBlock.HANGUL_JAMO)
        || containsUnicodeBlock(text, Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO);
  }

  private boolean containsUnicodeBlock(String text, Character.UnicodeBlock blockToFind) {
    if (!StringUtils.isSet(text) || blockToFind == null)
      return false;

    for (int i = 0; i < text.length(); i++) {
      if (Character.UnicodeBlock.of(text.charAt(i)) == blockToFind)
        return true;
    }

    return false;
  }

  private boolean containsLatinLetters(String text) {
    if (!StringUtils.isSet(text))
      return false;

    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z'))
        return true;

      Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
      if (block == Character.UnicodeBlock.LATIN_1_SUPPLEMENT || block == Character.UnicodeBlock.LATIN_EXTENDED_A || block == Character.UnicodeBlock.LATIN_EXTENDED_B)
        return true;
    }

    return false;
  }

  private String buildToken(String text) {
    long base = 0L;
    long value = base;
    int[] bytes = toUtf8Bytes(text);
    for (int i = 0; i < bytes.length; i++) {
      value += bytes[i];
      value = applyTokenOp(value, "+-a^+6"); //$NON-NLS-1$
    }

    value = applyTokenOp(value, "+-3^+b+-f"); //$NON-NLS-1$
    value ^= 0L;
    if (value < 0)
      value = (value & 2147483647L) + 2147483648L;

    value %= 1000000L;
    return value + "." + (value ^ base); //$NON-NLS-1$
  }

  private long applyTokenOp(long value, String seed) {
    for (int i = 0; i < seed.length() - 2; i += 3) {
      char third = seed.charAt(i + 2);
      long shift = third >= 'a' ? third - 87 : Long.parseLong(String.valueOf(third));
      long shifted = seed.charAt(i + 1) == '+' ? value >>> shift : value << shift;
      value = seed.charAt(i) == '+' ? (value + shifted) & 4294967295L : value ^ shifted;
    }

    return value;
  }

  private int[] toUtf8Bytes(String text) {
    List<Integer> bytes = new ArrayList<Integer>();
    for (int i = 0; i < text.length(); i++) {
      int codePoint = text.charAt(i);
      if (codePoint < 128) {
        bytes.add(Integer.valueOf(codePoint));
      } else if (codePoint < 2048) {
        bytes.add(Integer.valueOf(codePoint >> 6 | 192));
        bytes.add(Integer.valueOf(codePoint & 63 | 128));
      } else if ((codePoint & 64512) == 55296 && i + 1 < text.length() && (text.charAt(i + 1) & 64512) == 56320) {
        codePoint = 65536 + ((codePoint & 1023) << 10) + (text.charAt(++i) & 1023);
        bytes.add(Integer.valueOf(codePoint >> 18 | 240));
        bytes.add(Integer.valueOf(codePoint >> 12 & 63 | 128));
        bytes.add(Integer.valueOf(codePoint >> 6 & 63 | 128));
        bytes.add(Integer.valueOf(codePoint & 63 | 128));
      } else {
        bytes.add(Integer.valueOf(codePoint >> 12 | 224));
        bytes.add(Integer.valueOf(codePoint >> 6 & 63 | 128));
        bytes.add(Integer.valueOf(codePoint & 63 | 128));
      }
    }

    int[] result = new int[bytes.size()];
    for (int i = 0; i < bytes.size(); i++)
      result[i] = bytes.get(i).intValue();

    return result;
  }
}
