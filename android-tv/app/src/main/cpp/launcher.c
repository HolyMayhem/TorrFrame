#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <unistd.h>

int byedpi_main(int argc, char **argv);
const char *torrframe_proxy_token;

int main(int argc, char **argv) {
    const char *parent = getenv("TORRFRAME_PROXY_PARENT");
    torrframe_proxy_token = getenv("TORRFRAME_PROXY_TOKEN");
    if (!parent || !torrframe_proxy_token || strlen(torrframe_proxy_token) != 32) {
        return 1;
    }
    if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != strtol(parent, NULL, 10)) {
        return 1;
    }
    return byedpi_main(argc, argv);
}
