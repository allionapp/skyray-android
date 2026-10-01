package com.v2ray.ang.util

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import com.v2ray.ang.handler.AdSignalOverride
import java.util.Locale

open class MyContextWrapper(base: Context?) : ContextWrapper(base) {
    companion object {
        /**
         * Wraps the context with a new locale.
         *
         * @param context The original context.
         * @param newLocale The new locale to set.
         * @return A ContextWrapper with the new locale.
         */
        fun wrap(context: Context, newLocale: Locale?): ContextWrapper {
            var mContext = context
            val res: Resources = mContext.resources
            val configuration: Configuration = res.configuration

            val locale = newLocale ?: Locale.getDefault()
            configuration.setLocale(locale)
            val localeList = LocaleList(locale)
            LocaleList.setDefault(localeList)
            // That also reset the process locale, which the ad signals may be holding at the exit's.
            AdSignalOverride.reassert()
            configuration.setLocales(localeList)

            mContext = mContext.createConfigurationContext(configuration)
            return ContextWrapper(mContext)
        }
    }
}