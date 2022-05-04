public/chart.svg: ps.cljs
	UPDATEONLY=1 npm run update

updater:
	while [ 1 ]; do npm run update; sleep 60; done

watch:
	while true; do $(MAKE) -q || $(MAKE); sleep 0.5; done
