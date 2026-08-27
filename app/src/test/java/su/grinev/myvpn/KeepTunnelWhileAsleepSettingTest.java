package su.grinev.myvpn;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;

import su.grinev.myvpn.settings.SharedPreferencesSettingsProvider;

/** Persistence of the "keep the tunnel up while asleep" switch, including its default. */
public class KeepTunnelWhileAsleepSettingTest {

    private SharedPreferences mockPrefs;
    private SharedPreferences.Editor mockEditor;
    private SharedPreferencesSettingsProvider provider;

    @Before
    public void setUp() {
        mockPrefs = mock(SharedPreferences.class);
        mockEditor = mock(SharedPreferences.Editor.class);

        when(mockPrefs.edit()).thenReturn(mockEditor);
        when(mockEditor.putBoolean(anyString(), anyBoolean())).thenReturn(mockEditor);
        when(mockEditor.commit()).thenReturn(true);

        Context mockContext = mock(Context.class);
        when(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs);

        provider = new SharedPreferencesSettingsProvider(mockContext);
    }

    @Test
    public void defaultsToKeepingTheTunnel() {
        when(mockPrefs.getBoolean(eq(SharedPreferencesSettingsProvider.KEY_KEEP_TUNNEL_WHILE_ASLEEP), anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        assertTrue("a fresh install must not drop sessions on the first screen-off",
                provider.isKeepTunnelWhileAsleep());
    }

    @Test
    public void readsTheStoredValue() {
        when(mockPrefs.getBoolean(eq(SharedPreferencesSettingsProvider.KEY_KEEP_TUNNEL_WHILE_ASLEEP), anyBoolean()))
                .thenReturn(false);

        assertFalse(provider.isKeepTunnelWhileAsleep());
    }

    @Test
    public void savesTheValue() {
        assertTrue(provider.saveKeepTunnelWhileAsleep(false));

        verify(mockEditor).putBoolean(SharedPreferencesSettingsProvider.KEY_KEEP_TUNNEL_WHILE_ASLEEP, false);
        verify(mockEditor).commit();
    }
}
