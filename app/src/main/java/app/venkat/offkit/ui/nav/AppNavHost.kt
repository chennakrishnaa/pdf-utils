package app.venkat.offkit.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.venkat.offkit.feature.addimages.AddImagesScreen
import app.venkat.offkit.feature.compress.CompressScreen
import app.venkat.offkit.feature.imagestopdf.ImagesToPdfScreen
import app.venkat.offkit.feature.merge.MergeScreen
import app.venkat.offkit.ui.about.AboutScreen
import app.venkat.offkit.ui.home.HomeScreen
import app.venkat.offkit.ui.support.SupportScreen

/** Navigation route names. */
object Routes {
    const val HOME = "home"
    const val IMAGES_TO_PDF = "images-to-pdf"
    const val COMPRESS = "compress"
    const val MERGE = "merge"
    const val ADD_IMAGES = "add-images"
    const val ABOUT = "about"
    const val SUPPORT = "support"
}

/** The app's navigation graph, starting at [Routes.HOME]. */
@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = Routes.HOME, modifier = modifier) {
        composable(Routes.HOME) { entry ->
            // Ignore taps while a transition is in progress, so a double tap navigates once.
            val navigate: (String) -> Unit = { route ->
                if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    navController.navigate(route) { launchSingleTop = true }
                }
            }
            HomeScreen(
                onOpenTool = navigate,
                onOpenAbout = { navigate(Routes.ABOUT) },
                onOpenSupport = { navigate(Routes.SUPPORT) },
            )
        }
        composable(Routes.IMAGES_TO_PDF) {
            ImagesToPdfScreen(onBack = dropUnlessResumed { navController.popBackStack() })
        }
        composable(Routes.COMPRESS) {
            CompressScreen(onBack = dropUnlessResumed { navController.popBackStack() })
        }
        composable(Routes.MERGE) {
            MergeScreen(onBack = dropUnlessResumed { navController.popBackStack() })
        }
        composable(Routes.ADD_IMAGES) {
            AddImagesScreen(onBack = dropUnlessResumed { navController.popBackStack() })
        }
        composable(Routes.ABOUT) { entry ->
            AboutScreen(
                onBack = dropUnlessResumed { navController.popBackStack() },
                onOpenSupport = {
                    if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        navController.navigate(Routes.SUPPORT) { launchSingleTop = true }
                    }
                },
            )
        }
        composable(Routes.SUPPORT) {
            SupportScreen(onBack = dropUnlessResumed { navController.popBackStack() })
        }
    }
}
