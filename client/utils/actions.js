import { suggestions, stats } from "../../core"

let showClient = function() {
	let { pathname } = this.hashHistory.getCurrentLocation()
	if (pathname !== "/") {
		this.hashHistory.push("/")
	}
	this.store.dispatch(suggestions.listSuggestions())
}

let showStats = function() {
	let { pathname } = this.hashHistory.getCurrentLocation()
	if (pathname !== "/stats") {
		this.hashHistory.push("/stats")
		this.store.dispatch(stats.retrieve())
	}
}

let toggleNight = function(ignore = false) {
	let body = document.querySelector("body")
	let value = JSON.parse(window.localStorage.getItem("dark"))
	if (value === null) {
		value = false
	}

	if (ignore === false) {
		value = !value
	}

	if (body) {
		if (value) {
			body.classList.add("dark")
		} else {
			body.classList.remove("dark")
		}
	}

	window.localStorage.setItem("dark", value)
}

let actions = [
	{ func: "showClient", name: "Přejít k pokladně", impl: showClient },
	{ func: "showStats", name: "Zobrazit statistiky", impl: showStats },
	{ func: "toggleNight", name: "Přepnout tmavý režim", impl: toggleNight }
]

module.exports.bindActions = (store, hashHistory) => {

	//bind to window
	let windowObj = {}
	actions.forEach(a => {
		windowObj[a.func] = a.impl.bind({ store, hashHistory })
	})

	Object.assign(window, windowObj)

	//return payload
	return actions.map(({func, name}) => { return { func, name } })
}