package com.example.nepisem

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

enum class MealType { KAHVALTI, OGLE, AKSAM }

data class Recipe(
    val id: String,
    val name: String,
    val mealType: MealType,
    val ingredients: List<String>,
    val steps: List<String>,
    val durationMin: Int? = null,
    val notes: String? = null,
    val isVegetarian: Boolean = false,
    val isVegan: Boolean = false,
    val glutenFree: Boolean = false,
    val lactoseFree: Boolean = false,
    val cost: String = "₺",
    val calories: Int = 0,
    val synthetic: Boolean = false
)

private val ComponentActivity.dataStore by preferencesDataStore(name = "prefs")

class RecipeRepository(private val activity: ComponentActivity) {
    private val gson = Gson()
    private val favKey = stringSetPreferencesKey("favorite_ids")
    private val historyKey = stringPreferencesKey("history_json")
    private var cache: List<Recipe>? = null

    data class HistoryEntry(val id: String, val meal: String, val day: Int)
    data class History(val entries: MutableList<HistoryEntry> = mutableListOf())

    suspend fun loadRecipes(): List<Recipe> {
        cache?.let { return it }
        val json = activity.assets.open("recipes.json").bufferedReader().use { it.readText() }
        val listType = object : TypeToken<List<RecipeRaw>>() {}.type
        val raw: List<RecipeRaw> = gson.fromJson(json, listType)
        val base = raw.map { it.toRecipe() }
        val expanded = ensureMinPerMeal(base, minPerMeal = 50)
        cache = expanded
        return expanded
    }

    private fun ensureMinPerMeal(list: List<Recipe>, minPerMeal: Int): List<Recipe> {
        val result = list.toMutableList()
        MealType.values().forEach { meal ->
            val pool = result.filter { it.mealType == meal }
            var n = pool.size
            if (n < minPerMeal && n > 0) {
                val template = pool
                var i = 1
                while (n < minPerMeal) {
                    val base = template[i % template.size]
                    val newId = base.id + "_v" + i
                    result.add(base.copy(
                        id = newId,
                        name = base.name + " (çeşit #" + (i + 1) + ")",
                        synthetic = true
                    ))
                    n++; i++
                }
            }
        }
        return result
    }

    suspend fun getFavorites(): Set<String> {
        val prefs = activity.dataStore.data.first()
        return prefs[favKey] ?: emptySet()
    }

    suspend fun toggleFavorite(id: String) {
        activity.dataStore.edit { prefs ->
            val set = prefs[favKey]?.toMutableSet() ?: mutableSetOf()
            if (!set.add(id)) set.remove(id)
            prefs[favKey] = set
        }
    }

    private fun nowEpochDay(): Int = (System.currentTimeMillis() / TimeUnit.DAYS.toMillis(1)).toInt()

    suspend fun getHistory(): History {
        val prefs = activity.dataStore.data.first()
        val txt = prefs[historyKey] ?: return History()
        return runCatching { gson.fromJson(txt, History::class.java) }.getOrElse { History() }
    }

    suspend fun saveHistory(h: History) {
        val cutoff = nowEpochDay() - 30
        h.entries.removeAll { it.day < cutoff }
        activity.dataStore.edit { it[historyKey] = gson.toJson(h) }
    }

    suspend fun recordShown(recipe: Recipe) {
        val h = getHistory()
        h.entries.add(HistoryEntry(recipe.id, recipe.mealType.name, nowEpochDay()))
        saveHistory(h)
    }

    suspend fun clearHistory() { activity.dataStore.edit { it.remove(historyKey) } }

    suspend fun recentIds(meal: MealType, days: Int = 7): Set<String> {
        val h = getHistory()
        val minDay = nowEpochDay() - (days - 1)
        return h.entries.filter { it.meal == meal.name && it.day >= minDay }.map { it.id }.toSet()
    }

    data class RecipeRaw(
        val id: String,
        val name: String,
        val mealType: String,
        val ingredients: List<String>,
        val steps: List<String>,
        val durationMin: Int?,
        val notes: String?,
        val isVegetarian: Boolean?,
        val isVegan: Boolean?,
        val glutenFree: Boolean?,
        val lactoseFree: Boolean?,
        val cost: String?,
        val calories: Int?
    ) {
        fun toRecipe() = Recipe(
            id = id,
            name = name,
            mealType = MealType.valueOf(mealType),
            ingredients = ingredients,
            steps = steps,
            durationMin = durationMin,
            notes = notes,
            isVegetarian = isVegetarian ?: false,
            isVegan = isVegan ?: false,
            glutenFree = glutenFree ?: false,
            lactoseFree = lactoseFree ?: false,
            cost = cost ?: "₺",
            calories = calories ?: 0
        )
    }
}

data class Filters(
    val vegetarian: Boolean = false,
    val vegan: Boolean = false,
    val glutenFree: Boolean = false,
    val lactoseFree: Boolean = false,
    val quickOnly: Boolean = false,
    val budget: Boolean = false
)

fun applyFilters(list: List<Recipe>, f: Filters): List<Recipe> = list.filter { r ->
    val vegOk = if (f.vegan) r.isVegan else if (f.vegetarian) (r.isVegetarian || r.isVegan) else true
    val quickOk = if (f.quickOnly) (r.durationMin ?: 999) <= 20 else true
    val budgetOk = if (f.budget) (r.cost == "₺" || r.cost == "₺₺") else true
    val gfOk = if (f.glutenFree) r.glutenFree else true
    val lfOk = if (f.lactoseFree) r.lactoseFree else true
    vegOk && quickOk && budgetOk && gfOk && lfOk
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = RecipeRepository(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val nav = rememberNavController()
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(selected = currentRoute(nav) == "home", onClick = { nav.navigate("home") { launchSingleTop = true } }, label = { Text(stringResource(R.string.screen_home)) }, icon = {})
                            NavigationBarItem(selected = currentRoute(nav) == "favorites", onClick = { nav.navigate("favorites") { launchSingleTop = true } }, label = { Text(stringResource(R.string.screen_favorites)) }, icon = {})
                            NavigationBarItem(selected = currentRoute(nav) == "planner", onClick = { nav.navigate("planner") { launchSingleTop = true } }, label = { Text(stringResource(R.string.screen_planner)) }, icon = {})
                            NavigationBarItem(selected = currentRoute(nav) == "settings", onClick = { nav.navigate("settings") { launchSingleTop = true } }, label = { Text(stringResource(R.string.screen_settings)) }, icon = {})
                        }
                    }
                ) { padding ->
                    NavHost(navController = nav, startDestination = "home", modifier = Modifier.padding(padding)) {
                        composable("home") { HomeScreen(repo) }
                        composable("favorites") { FavoritesScreen(repo) }
                        composable("planner") { PlannerScreen(repo) }
                        composable("settings") { SettingsScreen(repo) }
                    }
                }
            }
        }
    }
}

@Composable
fun currentRoute(navController: androidx.navigation.NavHostController): String? {
    val backStackEntry by navController.currentBackStackEntryAsState()
    return backStackEntry?.destination?.route
}

@Composable
fun HomeScreen(repo: RecipeRepository) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf<List<Recipe>>(emptyList()) }
    var filters by remember { mutableStateOf(Filters()) }
    var lastMeal: MealType? by remember { mutableStateOf(null) }
    var selected: Recipe? by remember { mutableStateOf(null) }
    var favs by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(Unit) {
        all = repo.loadRecipes()
        favs = repo.getFavorites()
    }

    fun pickNonRepeating(meal: MealType) {
        lastMeal = meal
        val pool0 = all.filter { it.mealType == meal }
        val pool = applyFilters(pool0, filters)
        scope.launch {
            val recent = repo.recentIds(meal, days = 7)
            val candidates = pool.filter { it.id !in recent }
            val choice = (if (candidates.isNotEmpty()) candidates else pool).randomOrNull()
            selected = choice
            choice?.let { repo.recordShown(it) }
            if (choice == null) Toast.makeText(ctx, ctx.getString(R.string.no_recipe_left), Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.pick_meal_hint), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.filters), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChipUI(stringResource(R.string.filter_vegan), filters.vegan) { filters = filters.copy(vegan = it, vegetarian = if (it) true else filters.vegetarian) }
                    FilterChipUI(stringResource(R.string.filter_veg), filters.vegetarian) { filters = filters.copy(vegetarian = it, vegan = if (!it) false else filters.vegan) }
                    FilterChipUI(stringResource(R.string.filter_gluten_free), filters.glutenFree) { filters = filters.copy(glutenFree = it) }
                    FilterChipUI(stringResource(R.string.filter_lactose_free), filters.lactoseFree) { filters = filters.copy(lactoseFree = it) }
                    FilterChipUI(stringResource(R.string.filter_quick), filters.quickOnly) { filters = filters.copy(quickOnly = it) }
                    FilterChipUI(stringResource(R.string.filter_budget), filters.budget) { filters = filters.copy(budget = it) }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(Modifier.weight(1f), onClick = { pickNonRepeating(MealType.KAHVALTI) }) { Text(stringResource(R.string.breakfast)) }
            Button(Modifier.weight(1f), onClick = { pickNonRepeating(MealType.OGLE) }) { Text(stringResource(R.string.lunch)) }
            Button(Modifier.weight(1f), onClick = { pickNonRepeating(MealType.AKSAM) }) { Text(stringResource(R.string.dinner)) }
        }

        Spacer(Modifier.height(16.dp))

        selected?.let { r ->
            RecipeCard(
                recipe = r,
                isFavorite = favs.contains(r.id),
                onToggleFavorite = {
                    scope.launch { repo.toggleFavorite(r.id); favs = repo.getFavorites() }
                },
                onAnother = { lastMeal?.let { pickNonRepeating(it) } },
                onShare = {
                    val text = buildString {
                        append(r.name).append("\n\n")
                        append(ctx.getString(R.string.ingredients)).append(":\n"); r.ingredients.forEach { append("• ").append(it).append('\n') }
                        append("\n").append(ctx.getString(R.string.steps)).append(":\n"); r.steps.forEachIndexed { i,s -> append("${i+1}. ").append(s).append('\n') }
                        append("\n").append(ctx.getString(R.string.calorie)).append(": ").append(r.calories).append(" kcal")
                    }
                    shareText(ctx, text)
                }
            )
        }
    }
}

@Composable
fun FavoritesScreen(repo: RecipeRepository) {
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf<List<Recipe>>(emptyList()) }
    var favs by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(Unit) { all = repo.loadRecipes(); favs = repo.getFavorites() }

    val list = all.filter { favs.contains(it.id) }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        if (list.isEmpty()) Text(stringResource(R.string.empty_favorites)) else {
            list.forEach { r ->
                RecipeCard(
                    recipe = r,
                    isFavorite = True,
                    onToggleFavorite = { scope.launch { repo.toggleFavorite(r.id); favs = repo.getFavorites() } },
                    onAnother = {},
                    onShare = {}
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
fun SettingsScreen(repo: RecipeRepository) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.screen_settings), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { scope.launch { repo.clearHistory(); Toast.makeText(ctx, ctx.getString(R.string.history_cleared), Toast.LENGTH_SHORT).show() } }) {
            Text(stringResource(R.string.clear_history))
        }
    }
}

@Composable
fun PlannerScreen(repo: RecipeRepository) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf<List<Recipe>>(emptyList()) }
    var filters by remember { mutableStateOf(Filters()) }
    var plan by remember { mutableStateOf<Map<String, Recipe?>>(emptyMap()) }

    var weeklyLimitText by remember { mutableStateOf("14000") }
    val weeklyLimit = weeklyLimitText.toIntOrNull() ?: 14000
    val dailyTarget = (weeklyLimit / 7)
    val bCap = (dailyTarget * 0.25).toInt()
    val lCap = (dailyTarget * 0.35).toInt()
    val dCap = (dailyTarget * 0.40).toInt()

    LaunchedEffect(Unit) { all = repo.loadRecipes() }

    fun pickFor(meal: MealType, cap: Int?): Recipe? {
        val pool = applyFilters(all.filter { it.mealType == meal }, filters)
        val candidates = pool.filter { cap == null || it.calories <= cap }
        return candidates.randomOrNull()
    }

    fun generateAny() {
        val days = listOf("Pzt","Sal","Çar","Per","Cum","Cmt","Paz")
        val newMap = mutableMapOf<String, Recipe?>()
        for (d in days) {
            newMap["$d-KAHVALTI"] = pickFor(MealType.KAHVALTI, null)
            newMap["$d-OGLE"] = pickFor(MealType.OGLE, null)
            newMap["$d-AKSAM"] = pickFor(MealType.AKSAM, null)
        }
        plan = newMap
        scope.launch { reserveWeek(repo, plan) }
    }

    fun generateWithinCalorie() {
        val days = listOf("Pzt","Sal","Çar","Per","Cum","Cmt","Paz")
        val newMap = mutableMapOf<String, Recipe?>()
        for (d in days) {
            newMap["$d-KAHVALTI"] = pickFor(MealType.KAHVALTI, bCap)
            newMap["$d-OGLE"] = pickFor(MealType.OGLE, lCap)
            newMap["$d-AKSAM"] = pickFor(MealType.AKSAM, dCap)
        }
        plan = newMap
        scope.launch { reserveWeek(repo, plan) }
    }

    fun shoppingList(): List<String> {
        val items = mutableMapOf<String, Int>()
        plan.values.filterNotNull().forEach { r -> r.ingredients.forEach { ing -> items[ing] = (items[ing] ?: 0) + 1 } }
        return items.entries.sortedByDescending { it.value }.map { (k,v) -> f"{k}  ×{v}" }
    }

    val totalWeekCalories = plan.values.filterNotNull().sumOf { it.calories }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.filters), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChipUI(stringResource(R.string.filter_vegan), filters.vegan) { filters = filters.copy(vegan = it, vegetarian = if (it) true else filters.vegetarian) }
                    FilterChipUI(stringResource(R.string.filter_veg), filters.vegetarian) { filters = filters.copy(vegetarian = it, vegan = if (!it) false else filters.vegan) }
                    FilterChipUI(stringResource(R.string.filter_gluten_free), filters.glutenFree) { filters = filters.copy(glutenFree = it) }
                    FilterChipUI(stringResource(R.string.filter_lactose_free), filters.lactoseFree) { filters = filters.copy(lactoseFree = it) }
                    FilterChipUI(stringResource(R.string.filter_quick), filters.quickOnly) { filters = filters.copy(quickOnly = it) }
                    FilterChipUI(stringResource(R.string.filter_budget), filters.budget) { filters = filters.copy(budget = it) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(value = weeklyLimitText, onValueChange = { weeklyLimitText = it.filter { ch -> ch.isDigit() } }, label = { Text(stringResource(R.string.weekly_calorie_limit)) }, placeholder = { Text("14000") }, singleLine = true)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.per_day_target, dailyTarget))
        Text("Kahvaltı ≤ ${bCap} kcal • Öğle ≤ ${lCap} kcal • Akşam ≤ ${dCap} kcal")

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { generateAny() }) { Text(stringResource(R.string.planner_generate)) }
            Button(onClick = { generateWithinCalorie() }) { Text(stringResource(R.string.planner_generate_calorie)) }
            Button(onClick = { shareText(ctx, shoppingList().joinToString("\n").ifEmpty { "-" }) }) { Text(stringResource(R.string.planner_share_list)) }
        }
        Spacer(Modifier.height(12.dp))

        if (plan.isEmpty()) Text("→ " + stringResource(R.string.planner_generate)) else {
            DayPlanTable(plan)
            Spacer(Modifier.height(8.dp))
            Text("Toplam (hafta): ${totalWeekCalories} kcal")
        }
    }
}

suspend fun reserveWeek(repo: RecipeRepository, plan: Map<String, Recipe?>) {
    val h = repo.getHistory()
    val nowDay = (System.currentTimeMillis() / TimeUnit.DAYS.toMillis(1)).toInt()
    plan.forEach { (key, r) ->
        r ?: return@forEach
        val meal = when {
            key.endsWith("KAHVALTI") -> MealType.KAHVALTI
            key.endsWith("OGLE") -> MealType.OGLE
            else -> MealType.AKSAM
        }
        for (offset in 0..6) {
            h.entries.add(RecipeRepository.HistoryEntry(r.id, meal.name, nowDay + offset))
        }
    }
    repo.saveHistory(h)
}

@Composable
fun FilterChipUI(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    FilterChip(selected = checked, onClick = { onCheckedChange(!checked) }, label = { Text(label) })
}

@Composable
fun RecipeCard(
    recipe: Recipe,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onAnother: () -> Unit,
    onShare: () -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(recipe.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.meal_label, when(recipe.mealType){ MealType.KAHVALTI -> stringResource(R.string.breakfast); MealType.OGLE -> stringResource(R.string.lunch); else -> stringResource(R.string.dinner) })
            )
            recipe.durationMin?.let { Spacer(Modifier.height(4.dp)); Text(stringResource(R.string.duration, it)) }
            Text(stringResource(R.string.kcal, recipe.calories))
            recipe.notes?.let { Spacer(Modifier.height(4.dp)); Text(stringResource(R.string.note, it)) }
            val tags = buildList {
                if (recipe.isVegan) add("Vegan") else if (recipe.isVegetarian) add("Vejetaryen")
                if (recipe.glutenFree) add("Glütensiz")
                if (recipe.lactoseFree) add("Laktozsuz")
                if (recipe.cost in listOf("₺","₺₺")) add("Bütçe")
                if (recipe.synthetic) add("Varyasyon")
            }.joinToString(" • ")
            if (tags.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text(tags) }

            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.ingredients), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp)); recipe.ingredients.forEach { Text("• $it") }

            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.steps), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp)); recipe.steps.forEachIndexed { i, s -> Text("${i+1}. $s") }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onAnother) { Text(stringResource(R.string.another_suggestion)) }
                OutlinedButton(onClick = onShare) { Text(stringResource(R.string.share_recipe)) }
                FilledTonalButton(onClick = onToggleFavorite) { Text(if (isFavorite) stringResource(R.string.remove_favorite) else stringResource(R.string.add_favorite)) }
            }
        }
    }
}

fun shareText(ctx: android.content.Context, text: String) {
    val sendIntent = Intent().apply { action = Intent.ACTION_SEND; putExtra(Intent.EXTRA_TEXT, text); type = "text/plain" }
    ctx.startActivity(Intent.createChooser(sendIntent, null))
}

@Composable
fun DayPlanTable(plan: Map<String, Recipe?>) {
    val days = listOf("Pzt","Sal","Çar","Per","Cum","Cmt","Paz")
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            days.forEach { d ->
                val b = plan["$d-KAHVALTI"]?.name ?: "-"
                val l = plan["$d-OGLE"]?.name ?: "-"
                val di = plan["$d-AKSAM"]?.name ?: "-"
                Text(d, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("• Kahvaltı: $b")
                Text("• Öğle: $l")
                Text("• Akşam: $di")
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun FlowRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    content: @Composable RowScope.() -> Unit
) { Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = horizontalArrangement) { content() } }
