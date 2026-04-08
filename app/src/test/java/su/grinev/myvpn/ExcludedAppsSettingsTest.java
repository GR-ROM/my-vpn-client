package su.grinev.myvpn;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import su.grinev.myvpn.settings.SharedPreferencesSettingsProvider;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class ExcludedAppsSettingsTest {

    private SharedPreferences mockPrefs;
    private SharedPreferences.Editor mockEditor;
    private SharedPreferencesSettingsProvider provider;

    @Before
    public void setUp() {
        mockPrefs = mock(SharedPreferences.class);
        mockEditor = mock(SharedPreferences.Editor.class);

        when(mockPrefs.edit()).thenReturn(mockEditor);
        when(mockEditor.putStringSet(anyString(), any())).thenReturn(mockEditor);
        when(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor);
        when(mockEditor.putInt(anyString(), anyInt())).thenReturn(mockEditor);
        when(mockEditor.commit()).thenReturn(true);

        Context mockContext = mock(Context.class);
        when(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs);

        provider = new SharedPreferencesSettingsProvider(mockContext);
    }

    // ---- getExcludedApps ----

    @Test
    public void getExcludedApps_nothingSaved_returnsEmptySet() {
        when(mockPrefs.getStringSet(
                eq(SharedPreferencesSettingsProvider.KEY_EXCLUDED_APPS),
                any())).thenReturn(Collections.emptySet());

        Set<String> result = provider.getExcludedApps();

        assertTrue(result.isEmpty());
    }

    @Test
    public void getExcludedApps_returnsStoredPackages() {
        Set<String> stored = new HashSet<>(Arrays.asList("com.vk.android", "ru.sberbank.android"));
        when(mockPrefs.getStringSet(
                eq(SharedPreferencesSettingsProvider.KEY_EXCLUDED_APPS),
                any())).thenReturn(stored);

        Set<String> result = provider.getExcludedApps();

        assertEquals(stored, result);
    }

    @Test
    public void getExcludedApps_returnsDefensiveCopy() {
        Set<String> stored = new HashSet<>(Collections.singletonList("com.vk.android"));
        when(mockPrefs.getStringSet(
                eq(SharedPreferencesSettingsProvider.KEY_EXCLUDED_APPS),
                any())).thenReturn(stored);

        Set<String> result = provider.getExcludedApps();
        result.add("com.injected.package");

        // Calling again should not see the injected value
        Set<String> result2 = provider.getExcludedApps();
        assertFalse(result2.contains("com.injected.package"));
    }

    // ---- saveExcludedApps ----

    @Test
    public void saveExcludedApps_storesWithCorrectKey() {
        Set<String> toSave = new HashSet<>(Arrays.asList("com.vk.android", "ru.sberbank.android"));

        provider.saveExcludedApps(toSave);

        verify(mockEditor).putStringSet(
                eq(SharedPreferencesSettingsProvider.KEY_EXCLUDED_APPS),
                eq(toSave));
        verify(mockEditor).commit();
    }

    @Test
    public void saveExcludedApps_emptySet_storesEmptySet() {
        provider.saveExcludedApps(Collections.emptySet());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> captor = ArgumentCaptor.forClass(Set.class);
        verify(mockEditor).putStringSet(
                eq(SharedPreferencesSettingsProvider.KEY_EXCLUDED_APPS),
                captor.capture());
        assertTrue(captor.getValue().isEmpty());
    }

    @Test
    public void saveExcludedApps_returnsTrue_onSuccess() {
        when(mockEditor.commit()).thenReturn(true);

        boolean result = provider.saveExcludedApps(Collections.singleton("com.vk.android"));

        assertTrue(result);
    }

    @Test
    public void saveExcludedApps_returnsFalse_onFailure() {
        when(mockEditor.commit()).thenReturn(false);

        boolean result = provider.saveExcludedApps(Collections.singleton("com.vk.android"));

        assertFalse(result);
    }
}
