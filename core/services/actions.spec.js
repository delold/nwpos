const actions = require("./actions")
const actionTypes = require("./actionTypes")

const suggestions = require("../suggestions/actionTypes")

const nock = require("nock")

const configureMockStore = require("redux-mock-store").default
const thunk = require("redux-thunk").default

const mockStore = configureMockStore([thunk])

test("print customer", () => {
	let customer = {
		status: "STAGE_TYPING",
		screen: 0, paid: 0,
		services: { print: false, eet: false, log: false },
		cart: { selection: 0, items: [] }
	}

	nock("http://localhost")
		.post("/print", { customer })
		.reply(200, "good job")

	let store = mockStore()
	return store.dispatch(actions.printCart(customer)).then(() => {
		expect(store.getActions()).toEqual([
			{ type: actionTypes.PRINT }
		])
	})
})


test("log customer", () => {
	let customer = {
		status: "COMMIT_END",
		screen: -234,
		paid: 358,
		services: {
			print: false,
			eet: false,
			log: false
		},
		cart: {
			selection: 1,
			items: [
				{ name: "", price: 123, qty: 1 },
				{ name: "", price: 1, qty: 1 }
			]
		},
	}

	let payload = {
		customer: {
			returned: -234,
			paid: 358,
			cart: {
				selection: 1,
				items: [
					{ name: "", price: 123, qty: 1 },
					{ name: "", price: 1, qty: 1 }
				]
			}
		}
	}

	let answer = {
		"a": [
			{ "name": "aviváž", "min_price": 50, "max_price": 50, "bought": 1, "total": 50 }
		],
		"b": [
			{ "name": "barva na vlasy", "min_price": 62, "max_price": 62, "bought": 1, "total": 62 },
			{ "name": "baterie", "min_price": 10, "max_price": 10, "bought": 1, "total": 10 },
			{ "name": "batoh", "min_price": 250, "max_price": 250, "bought": 1, "total": 250 }
		]
	}


	nock("http://localhost")
		.post("/store", payload)
		.reply(200, answer)

	let store = mockStore()

	return store.dispatch(actions.log(customer)).then(() => {
		let actions = store.getActions()

		expect(actions).toEqual([
			{ type: actionTypes.LOG, log: true },
			{ type: suggestions.SUGGEST, suggestions: answer }
		])
	})
})

