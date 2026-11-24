package tv.torrframe.phoneRemote.api

import org.json.JSONArray
import org.json.JSONObject
import tv.torrframe.features.torrentsearch.data.SearchComparison
import tv.torrframe.features.torrentsearch.data.SearchProbe

internal fun SearchComparison.json() = JSONObject()
    .put("query", query).put("firstMode", firstMode.name)
    .put("unique", unique).put("proxiedUnique", proxiedUnique ?: JSONObject.NULL)
    .put("directOnly", directOnly).put("proxyOnly", proxyOnly)
    .put("sources", JSONArray(sources.map {
        JSONObject().put("name", it.name).put("first", it.first.json())
            .put("second", it.second?.json() ?: JSONObject.NULL)
            .put("secureDirect", it.secureDirect?.json() ?: JSONObject.NULL)
    }))

private fun SearchProbe.json() = JSONObject()
    .put("ok", ok).put("count", count).put("millis", millis)
    .put("routes", JSONArray(routes.map { it.name })).put("strategies", JSONArray(strategies.map { it.name }))
    .put("secureDnsUsed", secureDnsUsed)
    .put("error", error?.name ?: JSONObject.NULL).put("httpStatus", httpStatus ?: JSONObject.NULL)
