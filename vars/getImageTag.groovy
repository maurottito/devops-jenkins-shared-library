/**
 * Returns the appropriate Docker image tag based on the current branch / PR context.
 *
 * Tag strategy:
 *   PR (any branch)      → pr-<PR_NUMBER>-<BUILD_NUMBER>         e.g. pr-42-7
 *   develop              → dev-<SHORT_COMMIT>-<BUILD_NUMBER>      e.g. dev-a1b2c3d-7
 *   release/<version>    → rc-<version>-<BUILD_NUMBER>           e.g. rc-1.2.0-7
 *   main                 → v<BUILD_NUMBER>                        e.g. v7
 *   other branch         → <safe-branch-name>-<BUILD_NUMBER>     e.g. feature-123-7
 */
def call() {
    def branchName  = env.BRANCH_NAME   ?: ''
    def commitShort = env.GIT_COMMIT?.take(7) ?: 'unknown'
    def buildNum    = env.BUILD_NUMBER  ?: '0'

    if (env.CHANGE_ID) {
        // Pull-request pipeline
        return "pr-${env.CHANGE_ID}-${buildNum}"
    } else if (branchName == 'develop') {
        return "dev-${commitShort}-${buildNum}"
    } else if (branchName.startsWith('release/')) {
        def version = branchName.replace('release/', '').replaceAll('[^a-zA-Z0-9._-]', '-')
        return "rc-${version}-${buildNum}"
    } else if (branchName == 'main') {
        return "v${buildNum}"
    } else {
        def safeBranch = branchName.replaceAll('[^a-zA-Z0-9._-]', '-').take(20)
        return "${safeBranch}-${buildNum}"
    }
}
