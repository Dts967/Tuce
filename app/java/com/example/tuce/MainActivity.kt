package com.example.tuce

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ==================== 数据模型 ====================
data class MediaFile(
    val id: Long,
    val uri: Uri,
    val name: String,
    val folder: String,
    val date: Long,
    val mime: String,
    val duration: Long? = null
) {
    val isVideo get() = mime.startsWith("video/")
}

data class Album(
    val name: String,
    val cover: Uri,
    val count: Int,
    val files: List<MediaFile>
)

// ==================== 媒体仓库 ====================
class MediaRepo {
    private val ctx = TuceApp.ctx

    suspend fun getAll(): List<MediaFile> = withContext(Dispatchers.IO) {
        val r = mutableListOf<MediaFile>()
        r += query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image")
        r += query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video")
        r.sortedByDescending { it.date }
    }

    suspend fun getAlbums(): List<Album> = withContext(Dispatchers.IO) {
        getAll().groupBy { it.folder }.map { (name, files) ->
            Album(name, files.first().uri, files.size, files)
        }.sortedByDescending { it.files.maxOf { f -> f.date } }
    }

    private fun query(uri: Uri, type: String): List<MediaFile> {
        val c = ctx.contentResolver.query(
            uri,
            arrayOf("_id", "_display_name", "_data", "date_added", "mime_type", "_size", "duration"),
            null, null, "date_added DESC"
        ) ?: return emptyList()
        
        val list = mutableListOf<MediaFile>()
        c.use {
            while (it.moveToNext()) list.add(it.toMedia())
        }
        return list
    }

    private fun Cursor.toMedia(): MediaFile {
        val id = getLong(0)
        val name = getString(1)
        val path = getString(2)
        val date = getLong(3) * 1000
        val mime = getString(4)
        val folder = File(path).parentFile?.name ?: "未知"
        val contentUri = if (mime.startsWith("image/")) 
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI 
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val dur = if (getColumnIndex("duration") != -1 && mime.startsWith("video/")) 
            getLong(getColumnIndex("duration")) else null
        return MediaFile(id, ContentUris.withAppendedId(contentUri, id), name, folder, date, mime, dur)
    }
}

// ==================== 设置存储 ====================
private val Context.ds by preferencesDataStore("tuce")
class Prefs {
    private val ds = TuceApp.ctx.ds
    companion object {
        val DYNAMIC = booleanPreferencesKey("dynamic")
        val SHOW_NAME = booleanPreferencesKey("show_name")
    }
    val dynamic = ds.data.map { it[DYNAMIC] ?: true }
    val showName = ds.data.map { it[SHOW_NAME] ?: false }
    suspend fun setDynamic(v: Boolean) = ds.edit { it[DYNAMIC] = v }
    suspend fun setShowName(v: Boolean) = ds.edit { it[SHOW_NAME] = v }
}

// ==================== ViewModel ====================
class MainVM : ViewModel() {
    private val repo = MediaRepo()
    private val prefs = Prefs()
    private val scope = viewModelScope

    var albums by mutableStateOf<List<Album>>(emptyList()); private set
    var media by mutableStateOf<List<MediaFile>>(emptyList()); private set
    var dynamic by mutableStateOf(true); private set
    var showName by mutableStateOf(false); private set

    init {
        scope.launch {
            prefs.dynamic.collect { dynamic = it }
        }
        scope.launch {
            prefs.showName.collect { showName = it }
        }
        load()
    }

    fun load() {
        scope.launch {
            albums = repo.getAlbums()
            media = repo.getAll()
        }
    }

    fun toggleDynamic(v: Boolean) {
        scope.launch { prefs.setDynamic(v) }
    }

    fun toggleShowName(v: Boolean) {
        scope.launch { prefs.setShowName(v) }
    }
}

// ==================== 权限 ====================
object Perm {
    fun hasAllFiles(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) android.os.Environment.isExternalStorageManager()
        else false
    
    fun canRequest(): Boolean = Build.VERSION.SDK_INT >= 33
    
    fun intent(): Intent =
        if (Build.VERSION.SDK_INT >= 33)
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${TuceApp.ctx.packageName}")
            }
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${TuceApp.ctx.packageName}")
        }
}

// ==================== 主题 ====================
@Composable
fun TuceTheme(dark: Boolean = isSystemInDarkTheme(), dyn: Boolean = true, content: @Composable () -> Unit) {
    val scheme = when {
        dyn && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(TuceApp.ctx) else dynamicLightColorScheme(TuceApp.ctx)
        dark -> darkScheme
        else -> lightScheme
    }
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val w = (view.context as ComponentActivity).window
            w.statusBarColor = scheme.surface.toArgb()
            WindowCompat.getInsetsController(w, view).isAppearanceLightStatusBars = !dark
        }
    }
    
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}

private val lightScheme = lightColorScheme(
    primary = Color(0xFF6750A4), onPrimary = Color.White, primaryContainer = Color(0xFFEADDFF),
    background = Color(0xFFFFFBFE), surface = Color(0xFFFFFBFE), onSurface = Color(0xFF1C1B1F)
)
private val darkScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF), onPrimary = Color(0xFF381E72), primaryContainer = Color(0xFF4F378B),
    background = Color(0xFF1C1B1F), surface = Color(0xFF1C1B1F), onSurface = Color(0xFFE6E1E5)
)

// ==================== 主Activity ====================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: MainVM = viewModel()
            TuceTheme(dyn = vm.dynamic) {
                Scaffold(
                    bottomBar = { BottomNav() }
                ) { pad ->
                    Box(Modifier.padding(pad)) {
                        when (currentRoute()) {
                            "albums" -> AlbumsScreen(vm)
                            "preview" -> PreviewScreen(vm)
                            "settings" -> SettingsScreen(vm)
                        }
                    }
                }
            }
        }
    }
}

// ==================== 底部导航 ====================
var currentRoute = mutableStateOf("albums")

@Composable
fun BottomNav() {
    NavigationBar {
        NavigationBarItem(
            selected = currentRoute.value == "albums",
            onClick = { currentRoute.value = "albums" },
            icon = { Icon(Icons.Filled.PhotoLibrary, null) },
            label = { Text("图册") }
        )
        NavigationBarItem(
            selected = currentRoute.value == "preview",
            onClick = { currentRoute.value = "preview" },
            icon = { Icon(Icons.Filled.Preview, null) },
            label = { Text("预览") }
        )
        NavigationBarItem(
            selected = currentRoute.value == "settings",
            onClick = { currentRoute.value = "settings" },
            icon = { Icon(Icons.Filled.Settings, null) },
            label = { Text("设置") }
        )
    }
}

// ==================== 图册页 ====================
@Composable
fun AlbumsScreen(vm: MainVM) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(vm.albums) { album ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { },
                shape = RoundedCornerShape(12.dp)
            ) {
                Column {
                    AsyncImage(
                        model = album.cover,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        contentScale = ContentScale.Crop
                    )
                    Column(Modifier.padding(12.dp)) {
                        Text(album.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("${album.count}项", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ==================== 预览页 ====================
@Composable
fun PreviewScreen(vm: MainVM) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(vm.media) { m ->
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { }
            ) {
                AsyncImage(
                    model = m.uri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                if (vm.showName) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                        color = MaterialTheme.colorScheme.scrim.copy(0.6f)
                    ) {
                        Text(m.name, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, modifier = Modifier.padding(4.dp))
                    }
                }
                if (m.isVideo) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomEnd),
                        color = MaterialTheme.colorScheme.scrim.copy(0.7f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(formatDur(m.duration ?: 0), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(4.dp, 2.dp))
                    }
                }
            }
        }
    }
}

fun formatDur(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

// ==================== 设置页 ====================
@Composable
fun SettingsScreen(vm: MainVM) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("外观", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("动态配色", fontSize = 16.sp)
                Switch(vm.dynamic, onCheckedChange = { vm.toggleDynamic(it) })
            }
        }
        item {
            Text("预览", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("显示文件名", fontSize = 16.sp)
                Switch(vm.showName, onCheckedChange = { vm.toggleShowName(it) })
            }
        }
        item {
            Text("权限", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("所有文件访问", fontSize = 16.sp)
                    Text(
                        if (Perm.canRequest()) "允许应用管理所有文件" else "当前系统不支持",
                        fontSize = 12.sp,
                        color = if (Perm.canRequest()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline
                    )
                }
                if (Perm.canRequest()) {
                    FilledTonalButton(onClick = {
                        TuceApp.ctx.startActivity(Perm.intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) {
                        Text(if (Perm.hasAllFiles()) "已授权" else "去授权")
                    }
                } else {
                    Text("不可用", fontSize = 14.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}
